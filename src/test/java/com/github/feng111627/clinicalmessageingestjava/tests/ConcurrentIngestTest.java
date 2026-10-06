package com.github.feng111627.clinicalmessageingestjava.tests;

import com.github.feng111627.clinicalmessageingestjava.hl7.Hl7Parser;
import com.github.feng111627.clinicalmessageingestjava.ingest.BatchIngestResult;
import com.github.feng111627.clinicalmessageingestjava.ingest.IngestResult;
import com.github.feng111627.clinicalmessageingestjava.ingest.IngestStatus;
import com.github.feng111627.clinicalmessageingestjava.ingest.MessageIngestService;
import com.github.feng111627.clinicalmessageingestjava.merge.PatientMergeService;
import com.github.feng111627.clinicalmessageingestjava.store.ConflictStore;
import com.github.feng111627.clinicalmessageingestjava.store.MessageStore;
import com.github.feng111627.clinicalmessageingestjava.store.PatientStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多线程并发接入：同一患者被并发更新时，
 * 不允许旧数据反盖新数据、同一条消息被重复计数、消息丢失。
 */
class ConcurrentIngestTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentIngestTest.class);

    private MessageStore messageStore;
    private PatientStore patientStore;
    private ConflictStore conflictStore;
    private MessageIngestService service;

    @BeforeEach
    void setUp() {
        messageStore = new MessageStore();
        patientStore = new PatientStore();
        conflictStore = new ConflictStore();
        service = new MessageIngestService(new Hl7Parser(), messageStore,
                new PatientMergeService(patientStore), conflictStore,
                MessageIngestService.DEFAULT_MAX_BATCH_SIZE);
    }

    private String msg(String controlId, String msh7, String patientId, String name, String gender) {
        return "MSH|^~\\&|HIS|HOSPITAL|RCV|HOSPITAL|" + msh7 + "||ADT^A01|" + controlId + "|P|2.5\r"
                + "PID|1||" + patientId + "||" + name + "||19800101|" + gender;
    }

    /** 用同一道闸门并发执行任务，收集每个任务的返回。 */
    private <T> List<T> runConcurrently(List<java.util.concurrent.Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch gate = new CountDownLatch(1);
        List<Callable<T>> gated = tasks.stream()
                .<Callable<T>>map(t -> () -> {
                    gate.await();
                    return t.call();
                })
                .toList();
        List<Future<T>> futures = gated.stream().map(pool::submit).toList();
        gate.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get());
        }
        return results;
    }

    private Map<IngestStatus, Long> countByStatus(List<IngestResult> results) {
        return results.stream().collect(Collectors.groupingBy(IngestResult::status,
                Collectors.counting()));
    }

    @RepeatedTest(3)
    void sameMessageFromManyThreads_countedExactlyOnce() throws Exception {
        String raw = msg("T001", "20261002090000", "P400^^^HIS", "Zhang^San", "M");
        int threads = 16;
        List<Callable<IngestResult>> tasks = Collections.nCopies(threads,
                () -> service.ingest(raw));

        List<IngestResult> results = runConcurrently(tasks);
        Map<IngestStatus, Long> counts = countByStatus(results);
        log.info("same-message concurrent counts={}", counts);

        // 恰好一次被当成新消息，其余全部是重发：同一条消息不被重复计数
        assertThat(counts.getOrDefault(IngestStatus.ACCEPTED_NEW, 0L)).isEqualTo(1);
        assertThat(counts.getOrDefault(IngestStatus.DUPLICATE_RESENT, 0L)).isEqualTo(threads - 1L);
        assertThat(messageStore.size()).isEqualTo(1);
        assertThat(patientStore.size()).isEqualTo(1);
        assertThat(messageStore.findByControlId("T001")).hasSize(1);
    }

    @RepeatedTest(5)
    void concurrentDistinctUpdates_oldNeverOverwritesNew_nothingLost() throws Exception {
        int n = 12;
        // 业务时间两两不同 -> 最终状态确定：最靠后的那条
        List<String> raws = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            raws.add(msg("T1" + String.format("%02d", i),
                    String.format("20261002%02d0000", i), "P401^^^HIS", "Name^" + i, "M"));
        }
        Collections.shuffle(raws, new java.util.Random(System.nanoTime()));
        List<Callable<IngestResult>> tasks = raws.stream()
                .<Callable<IngestResult>>map(raw -> () -> service.ingest(raw))
                .toList();

        List<IngestResult> results = runConcurrently(tasks);
        Map<IngestStatus, Long> counts = countByStatus(results);
        log.info("distinct-updates concurrent counts={}", counts);

        // 消息不丢：每条都有结论；全部首次到达
        assertThat(results).hasSize(n);
        assertThat(counts.getOrDefault(IngestStatus.ACCEPTED_NEW, 0L)
                + counts.getOrDefault(IngestStatus.IGNORED_STALE, 0L)).isEqualTo((long) n);
        assertThat(messageStore.size()).isEqualTo(n);
        // 最终状态确定：业务时间最靠后的那条，旧数据不得反盖
        var record = patientStore.find("P401^^^HIS").orElseThrow();
        assertThat(record.getName()).isEqualTo("Name^" + (n - 1));
        assertThat(record.getLastUpdated().toString()).isEqualTo("2026-10-02T" + String.format("%02d", n - 1) + ":00");
    }

    @Test
    void concurrentSameTimeConflicts_exactlyOneApplied_restConflicted() throws Exception {
        int n = 8;
        List<Callable<IngestResult>> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String raw = msg("T2" + i, "20261002090000", "P402^^^HIS", "Contend^" + i, "M");
            tasks.add(() -> service.ingest(raw));
        }
        List<IngestResult> results = runConcurrently(tasks);
        Map<IngestStatus, Long> counts = countByStatus(results);
        log.info("same-time-conflict concurrent counts={}", counts);

        // 恰好一条应用，其余全部判冲突并留痕
        assertThat(counts.getOrDefault(IngestStatus.ACCEPTED_NEW, 0L)).isEqualTo(1);
        assertThat(counts.getOrDefault(IngestStatus.CONFLICT_UPDATE, 0L)).isEqualTo(n - 1L);
        assertThat(conflictStore.size()).isEqualTo(n - 1);
        // 冲突不卡死患者：更靠后的更新照常生效
        IngestResult later = service.ingest(msg("T299", "20261002120000", "P402^^^HIS", "Final^Name", "F"));
        assertThat(later.status()).isEqualTo(IngestStatus.ACCEPTED_NEW);
        assertThat(patientStore.find("P402^^^HIS").orElseThrow().getName()).isEqualTo("Final^Name");
    }

    @Test
    void concurrentIdReuse_onlyOriginalCounts() throws Exception {
        String original = msg("T301", "20261002090000", "P403^^^HIS", "Real^Name", "M");
        int reusers = 6;
        List<Callable<IngestResult>> tasks = new ArrayList<>();
        tasks.add(() -> service.ingest(original));
        for (int i = 0; i < reusers; i++) {
            String reused = msg("T301", "2026100209" + String.format("%02d", i + 10),
                    "P403^^^HIS", "Fake^" + i, "F");
            tasks.add(() -> service.ingest(reused));
        }
        Collections.shuffle(tasks, new java.util.Random(42));

        List<IngestResult> results = runConcurrently(tasks);
        Map<IngestStatus, Long> counts = countByStatus(results);
        log.info("id-reuse concurrent counts={}", counts);

        // 无论谁先抢到编号：恰好一条 NEW 结论，其余都是编号复用拒收，已入册数据不动
        assertThat(counts.getOrDefault(IngestStatus.ACCEPTED_NEW, 0L)
                + counts.getOrDefault(IngestStatus.IGNORED_STALE, 0L)).isEqualTo(1);
        assertThat(counts.getOrDefault(IngestStatus.REJECTED_ID_REUSE, 0L)).isEqualTo((long) reusers);
        assertThat(messageStore.size()).isEqualTo(1);
        assertThat(patientStore.size()).isEqualTo(1);
    }

    @RepeatedTest(3)
    void concurrentBatches_sameFinalStateAsSequential_noDoubleCount() throws Exception {
        // 两个批次有重叠（重发），被两个线程同时提交
        List<String> batchA = List.of(
                msg("T401", "20261002080000", "P404^^^HIS", "N^1", "M"),
                msg("T402", "20261002090000", "P404^^^HIS", "N^2", "F"),
                msg("T403", "20261002100000", "P404^^^HIS", "N^3", "M"));
        List<String> batchB = List.of(
                msg("T402", "20261002090000", "P404^^^HIS", "N^2", "F"), // 与 batchA 重发
                msg("T404", "20261002110000", "P404^^^HIS", "N^4", "F"),
                msg("T405", "20261002070000", "P404^^^HIS", "N^0", "M"));

        List<Callable<BatchIngestResult>> tasks = List.of(
                () -> service.ingestBatch(batchA),
                () -> service.ingestBatch(batchB));
        List<BatchIngestResult> results = runConcurrently(tasks);
        assertThat(results).allMatch(BatchIngestResult::accepted);

        var concurrentRecord = patientStore.find("P404^^^HIS").orElseThrow();
        int concurrentSeen = messageStore.size();
        log.info("concurrent batches final name={} seen={}", concurrentRecord.getName(), concurrentSeen);

        // 对照：同一批消息逐条顺序接入（T401..T405 按编号序）
        setUp();
        for (String raw : List.of(
                msg("T401", "20261002080000", "P404^^^HIS", "N^1", "M"),
                msg("T402", "20261002090000", "P404^^^HIS", "N^2", "F"),
                msg("T403", "20261002100000", "P404^^^HIS", "N^3", "M"),
                msg("T404", "20261002110000", "P404^^^HIS", "N^4", "F"),
                msg("T405", "20261002070000", "P404^^^HIS", "N^0", "M"))) {
            service.ingest(raw);
        }
        var sequentialRecord = patientStore.find("P404^^^HIS").orElseThrow();

        // 最终状态一致：最靠后的 N^4；不同编号恰好 5 个，重发不重复计数
        assertThat(concurrentRecord.getName()).isEqualTo(sequentialRecord.getName()).isEqualTo("N^4");
        assertThat(concurrentRecord.getGender()).isEqualTo(sequentialRecord.getGender()).isEqualTo("F");
        assertThat(concurrentRecord.getLastUpdated()).isEqualTo(sequentialRecord.getLastUpdated());
        assertThat(concurrentSeen).isEqualTo(5);
    }
}
