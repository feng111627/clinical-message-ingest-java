package com.github.feng111627.clinicalmessageingestjava.tests;

import com.github.feng111627.clinicalmessageingestjava.hl7.Hl7Parser;
import com.github.feng111627.clinicalmessageingestjava.ingest.BatchIngestResult;
import com.github.feng111627.clinicalmessageingestjava.ingest.IngestResult;
import com.github.feng111627.clinicalmessageingestjava.ingest.IngestStatus;
import com.github.feng111627.clinicalmessageingestjava.ingest.MessageIngestService;
import com.github.feng111627.clinicalmessageingestjava.merge.PatientMergeService;
import com.github.feng111627.clinicalmessageingestjava.merge.PatientRecord;
import com.github.feng111627.clinicalmessageingestjava.store.ConflictStore;
import com.github.feng111627.clinicalmessageingestjava.store.MessageStore;
import com.github.feng111627.clinicalmessageingestjava.store.PatientStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 批量接入：乱序等价、容量上限、并发调度。 */
class BatchIngestTest {

    private static final Logger log = LoggerFactory.getLogger(BatchIngestTest.class);
    private static final int MAX_BATCH = 50;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 2, 0, 0);

    private MessageStore messageStore;
    private PatientStore patientStore;
    private ConflictStore conflictStore;
    private MessageIngestService service;

    @BeforeEach
    void setUp() {
        messageStore = new MessageStore();
        patientStore = new PatientStore();
        conflictStore = new ConflictStore();
        service = newService(messageStore, patientStore, conflictStore);
    }

    private MessageIngestService newService(MessageStore ms, PatientStore ps, ConflictStore cs) {
        return new MessageIngestService(new Hl7Parser(), ms, new PatientMergeService(ps), cs, MAX_BATCH);
    }

    private String msg(String controlId, String msh7, String patientId, String name, String gender) {
        return "MSH|^~\\&|HIS|HOSPITAL|RCV|HOSPITAL|" + msh7 + "||ADT^A01|" + controlId + "|P|2.5\r"
                + "PID|1||" + patientId + "||" + name + "||19800101|" + gender;
    }

    /** 业务时间 = BASE + minutes 分钟，保证时间戳合法且全局有序。 */
    private String msgAt(String controlId, int minutes, String patientId, String name, String gender) {
        return msg(controlId, BASE.plusMinutes(minutes).format(TS), patientId, name, gender);
    }

    // ---------- 批量与逐条接入结果一致 ----------

    @Test
    void batchUnorderedWithResendAndConflict_matchesOneByOneIngest() {
        // 批次内部乱序，夹一条一字不差的重发、一条同时间冲突消息
        List<String> batch = List.of(
                msgAt("B003", 120, "P300^^^HIS", "A^Three", "M"),
                msgAt("B001", 80, "P300^^^HIS", "A^One", "F"),
                msgAt("B003", 120, "P300^^^HIS", "A^Three", "M"), // 重发
                msgAt("B004", 120, "P300^^^HIS", "A^Evil", "F"),  // 同时间冲突
                msgAt("B002", 100, "P300^^^HIS", "A^Two", "F"));

        BatchIngestResult batchResult = service.ingestBatch(batch);
        assertThat(batchResult.accepted()).isTrue();
        assertThat(batchResult.results()).hasSize(5);
        batchResult.results().forEach(r ->
                log.info("batch controlId={} status={} detail={}", r.messageControlId(), r.status(), r.detail()));
        PatientRecord fromBatch = patientStore.find("P300^^^HIS").orElseThrow();

        // 对照组：同样顺序逐条接入到全新环境
        MessageStore ms2 = new MessageStore();
        PatientStore ps2 = new PatientStore();
        ConflictStore cs2 = new ConflictStore();
        MessageIngestService sequential = newService(ms2, ps2, cs2);
        List<IngestResult> oneByOne = batch.stream().map(sequential::ingest).toList();
        PatientRecord fromSequential = ps2.find("P300^^^HIS").orElseThrow();

        // 最终患者状态完全一致
        assertThat(fromBatch.getName()).isEqualTo(fromSequential.getName()).isEqualTo("A^Three");
        assertThat(fromBatch.getGender()).isEqualTo(fromSequential.getGender()).isEqualTo("M");
        assertThat(fromBatch.getLastUpdated()).isEqualTo(fromSequential.getLastUpdated());
        // 每条消息的结论也一致
        assertThat(batchResult.results()).extracting(IngestResult::status)
                .containsExactlyElementsOf(oneByOne.stream().map(IngestResult::status).toList());
        // 重发与冲突的结论可区分
        assertThat(batchResult.results().get(2).status()).isEqualTo(IngestStatus.DUPLICATE_RESENT);
        assertThat(batchResult.results().get(3).status()).isEqualTo(IngestStatus.CONFLICT_UPDATE);
        // 冲突留痕一致
        assertThat(conflictStore.findAll()).hasSize(1);
        assertThat(cs2.findAll()).hasSize(1);
    }

    @Test
    void batchArrivalOrderPermutations_sameFinalState() {
        List<String> messages = List.of(
                msgAt("P1", 10, "P301^^^HIS", "N^One", "M"),
                msgAt("P2", 20, "P301^^^HIS", "N^Two", "F"),
                msgAt("P3", 30, "P301^^^HIS", "N^Three", "M"),
                msgAt("P4", 40, "P301^^^HIS", "N^Four", "F"));

        List<List<String>> orders = new ArrayList<>();
        orders.add(messages); // 正序
        List<String> reversed = new ArrayList<>(messages);
        Collections.reverse(reversed);
        orders.add(reversed); // 反序
        orders.add(List.of(messages.get(2), messages.get(0), messages.get(3), messages.get(1))); // 交错
        orders.add(List.of(messages.get(3), messages.get(1), messages.get(2), messages.get(0))); // 乱序

        for (List<String> order : orders) {
            MessageStore ms = new MessageStore();
            PatientStore ps = new PatientStore();
            MessageIngestService svc = newService(ms, ps, new ConflictStore());
            BatchIngestResult r = svc.ingestBatch(order);
            assertThat(r.accepted()).isTrue();
            PatientRecord rec = ps.find("P301^^^HIS").orElseThrow();
            log.info("order statuses={} -> name={} gender={} time={}",
                    r.results().stream().map(x -> x.status().name()).toList(),
                    rec.getName(), rec.getGender(), rec.getLastUpdated());
            // 任意到达顺序，最终都是业务时间最新的 P4
            assertThat(rec.getName()).isEqualTo("N^Four");
            assertThat(rec.getGender()).isEqualTo("F");
            assertThat(rec.getLastUpdated()).isEqualTo(BASE.plusMinutes(40));
        }
    }

    // ---------- 容量上限 ----------

    @Test
    void batchExceedingCapacity_rejectedDeterministically_nothingProcessed() {
        List<String> oversized = IntStream.rangeClosed(1, MAX_BATCH + 1)
                .mapToObj(i -> msgAt("OV" + i, i, "P4" + i + "^^^HIS", "N^" + i, "M"))
                .toList();

        BatchIngestResult r = service.ingestBatch(oversized);
        log.info("oversized batch accepted={} reason={}", r.accepted(), r.rejectionReason());
        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectionReason()).contains(String.valueOf(MAX_BATCH + 1))
                .contains(String.valueOf(MAX_BATCH));
        assertThat(r.maxBatchSize()).isEqualTo(MAX_BATCH);
        assertThat(r.results()).isEmpty();
        // 一条都没处理
        assertThat(messageStore.size()).isZero();
        assertThat(patientStore.size()).isZero();

        // 同样输入再试一次，结论确定一致
        BatchIngestResult again = service.ingestBatch(oversized);
        assertThat(again.accepted()).isFalse();
        assertThat(again.rejectionReason()).isEqualTo(r.rejectionReason());
    }

    @Test
    void batchExactlyAtCapacity_accepted() {
        List<String> atCap = IntStream.rangeClosed(1, MAX_BATCH)
                .mapToObj(i -> msgAt("AT" + i, i, "P5" + i + "^^^HIS", "N^" + i, "M"))
                .toList();
        BatchIngestResult r = service.ingestBatch(atCap);
        assertThat(r.accepted()).isTrue();
        assertThat(r.results()).hasSize(MAX_BATCH);
        assertThat(patientStore.size()).isEqualTo(MAX_BATCH);
    }

    // ---------- 并发调度 ----------

    /** 并发提交 tasks，全部线程就绪后同时放行，返回每个任务抛出的异常（无异常则为空列表）。 */
    private List<Throwable> runInParallel(List<Runnable> tasks) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        for (Runnable task : tasks) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    task.run();
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        return List.copyOf(errors);
    }

    @Test
    void concurrentBatches_samePatient_noLostNoDuplicate_finalStateConsistent() throws Exception {
        int threads = 8, perThread = 10, total = threads * perThread;
        Queue<IngestResult> results = new ConcurrentLinkedQueue<>();
        List<Runnable> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int base = t * perThread;
            tasks.add(() -> {
                List<String> batch = new ArrayList<>();
                for (int i = 0; i < perThread; i++) {
                    int seq = base + i; // 业务时间全局唯一且递增
                    batch.add(msgAt("CC" + seq, seq, "P500^^^HIS", "N^" + seq, seq % 2 == 0 ? "M" : "F"));
                }
                service.ingestBatch(batch).results().forEach(results::add);
            });
        }

        assertThat(runInParallel(tasks)).isEmpty();

        // 无消息丢失：每条都有结论
        assertThat(results).hasSize(total);
        // 无重复计数：唯一编号数 == 消息总数
        assertThat(messageStore.size()).isEqualTo(total);
        // 旧数据不得反盖新数据：最终状态 == 业务时间最新的消息（seq=79）
        PatientRecord rec = patientStore.find("P500^^^HIS").orElseThrow();
        log.info("concurrent final name={} gender={} time={}", rec.getName(), rec.getGender(), rec.getLastUpdated());
        assertThat(rec.getName()).isEqualTo("N^79");
        assertThat(rec.getGender()).isEqualTo("F");
        assertThat(rec.getLastUpdated()).isEqualTo(BASE.plusMinutes(79));
    }

    @Test
    void concurrentIdenticalResends_exactlyOneAccepted_restDuplicate() throws Exception {
        String raw = msgAt("DUP1", 10, "P501^^^HIS", "Zhang^San", "M");
        int threads = 16;
        Queue<IngestResult> results = new ConcurrentLinkedQueue<>();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> results.add(service.ingest(raw)));
        }

        assertThat(runInParallel(tasks)).isEmpty();

        // 同一条消息并发投递：恰好一条被当作新消息，其余全部判重，不重复计数
        assertThat(results.stream().filter(r -> r.status() == IngestStatus.ACCEPTED_NEW)).hasSize(1);
        assertThat(results.stream().filter(r -> r.status() == IngestStatus.DUPLICATE_RESENT))
                .hasSize(threads - 1);
        assertThat(messageStore.size()).isEqualTo(1);
        assertThat(patientStore.size()).isEqualTo(1);
    }

    @Test
    void concurrentSameTimeConflictingUpdates_exactlyOneWins_conflictTraced() throws Exception {
        int threads = 10;
        Queue<IngestResult> results = new ConcurrentLinkedQueue<>();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int seq = i;
            // 同一患者、同一业务时间、内容两两不同
            tasks.add(() -> results.add(service.ingest(
                    msgAt("CT" + seq, 60, "P502^^^HIS", "N^" + seq, "M"))));
        }

        assertThat(runInParallel(tasks)).isEmpty();

        // 恰好一条生效，其余全部判为更新冲突；没有数据丢失或静默覆盖
        assertThat(results.stream().filter(r -> r.status() == IngestStatus.ACCEPTED_NEW)).hasSize(1);
        assertThat(results.stream().filter(r -> r.status() == IngestStatus.CONFLICT_UPDATE))
                .hasSize(threads - 1);
        // 每次冲突都有留痕，且都指向同一条已入册消息
        assertThat(conflictStore.findByPatientId("P502^^^HIS")).hasSize(threads - 1);
        assertThat(conflictStore.findByPatientId("P502^^^HIS"))
                .extracting(c -> c.existingControlId())
                .containsOnly(conflictStore.findByPatientId("P502^^^HIS").get(0).existingControlId());
        // 最终档案就是那条胜出的消息
        PatientRecord rec = patientStore.find("P502^^^HIS").orElseThrow();
        String winner = results.stream().filter(r -> r.status() == IngestStatus.ACCEPTED_NEW)
                .findFirst().orElseThrow().messageControlId();
        log.info("concurrent conflict winner={} finalName={}", winner, rec.getName());
        assertThat(rec.getName()).isEqualTo("N^" + winner.substring(2));
        assertThat(rec.getLastControlId()).isEqualTo(winner);
    }

    @Test
    void concurrentMixedSchedules_finalStateMatchesMaxBusinessTime() throws Exception {
        // 多种并发调度方式：纯单条并发、纯批量并发、单条+批量混合
        for (int round = 0; round < 3; round++) {
            setUp();
            String patient = "P60" + round + "^^^HIS";
            int messageCount = 40;
            List<String> all = new ArrayList<>();
            for (int i = 0; i < messageCount; i++) {
                all.add(msgAt("MX" + round + "_" + i, i, patient, "N^" + i, i % 2 == 0 ? "M" : "F"));
            }
            Collections.shuffle(all, new java.util.Random(round));

            Queue<IngestResult> results = new ConcurrentLinkedQueue<>();
            List<Runnable> tasks = new ArrayList<>();
            switch (round) {
                case 0 -> {
                    // 8 个线程，每个线程逐条接入 5 条
                    for (int t = 0; t < 8; t++) {
                        final List<String> chunk = all.subList(t * 5, t * 5 + 5);
                        tasks.add(() -> chunk.forEach(m -> results.add(service.ingest(m))));
                    }
                }
                case 1 -> {
                    // 4 个线程，每个线程提交一批 10 条
                    for (int t = 0; t < 4; t++) {
                        final List<String> chunk = all.subList(t * 10, t * 10 + 10);
                        tasks.add(() -> service.ingestBatch(chunk).results().forEach(results::add));
                    }
                }
                default -> {
                    // 混合：前 20 条单条并发，后 20 条分两批并发
                    for (int t = 0; t < 4; t++) {
                        final List<String> chunk = all.subList(t * 5, t * 5 + 5);
                        tasks.add(() -> chunk.forEach(m -> results.add(service.ingest(m))));
                    }
                    for (int t = 0; t < 2; t++) {
                        final List<String> chunk = all.subList(20 + t * 10, 20 + t * 10 + 10);
                        tasks.add(() -> service.ingestBatch(chunk).results().forEach(results::add));
                    }
                }
            }

            assertThat(runInParallel(tasks)).isEmpty();
            assertThat(results).hasSize(messageCount);            // 无消息丢失
            assertThat(messageStore.size()).isEqualTo(messageCount); // 无重复计数

            // 最终状态 == 业务时间最大的那条消息（seq=39），与调度方式无关
            PatientRecord rec = patientStore.find(patient).orElseThrow();
            log.info("round={} final name={} time={}", round, rec.getName(), rec.getLastUpdated());
            assertThat(rec.getName()).isEqualTo("N^39");
            assertThat(rec.getLastUpdated()).isEqualTo(BASE.plusMinutes(39));
        }
    }
}
