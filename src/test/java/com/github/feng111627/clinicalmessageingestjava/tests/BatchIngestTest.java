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
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 批量接入：乱序/重发/冲突混合批次与逐条接入结果一致；容量上限拒收确定。 */
class BatchIngestTest {

    private static final Logger log = LoggerFactory.getLogger(BatchIngestTest.class);

    private MessageStore messageStore;
    private PatientStore patientStore;
    private ConflictStore conflictStore;
    private MessageIngestService service;

    @BeforeEach
    void setUp() {
        messageStore = new MessageStore();
        patientStore = new PatientStore();
        conflictStore = new ConflictStore();
        service = newService(MessageIngestService.DEFAULT_MAX_BATCH_SIZE);
    }

    private MessageIngestService newService(int maxBatchSize) {
        return new MessageIngestService(new Hl7Parser(), messageStore,
                new PatientMergeService(patientStore), conflictStore, maxBatchSize);
    }

    private String msg(String controlId, String msh7, String patientId, String name, String gender) {
        return "MSH|^~\\&|HIS|HOSPITAL|RCV|HOSPITAL|" + msh7 + "||ADT^A01|" + controlId + "|P|2.5\r"
                + "PID|1||" + patientId + "||" + name + "||19800101|" + gender;
    }

    @Test
    void batchEqualsSequentialIngest_finalStateAndConclusions() {
        List<String> batch = List.of(
                msg("B001", "20261002120000", "P300^^^HIS", "Final^Name", "F"),
                msg("B002", "20261002080000", "P300^^^HIS", "Old^Name", "M"),
                msg("B001", "20261002120000", "P300^^^HIS", "Final^Name", "F"), // 一字不差的重发
                msg("B003", "20261002100000", "P300^^^HIS", "Mid^Name", "M"),
                msg("B004", "20261002120000", "P300^^^HIS", "Final^DIFF", "M"), // 与 B001 同时间冲突
                msg("B002", "20261002083000", "P300^^^HIS", "Reuse^Name", "F")); // 编号复用

        // 批量接入
        BatchIngestResult batchResult = service.ingestBatch(batch);
        assertThat(batchResult.accepted()).isTrue();
        assertThat(batchResult.results()).hasSize(batch.size());
        for (IngestResult r : batchResult.results()) {
            log.info("batch controlId={} status={} detail={}", r.messageControlId(), r.status(), r.detail());
        }
        var batchFinal = patientStore.find("P300^^^HIS").orElseThrow();
        int batchConflicts = conflictStore.size();
        int batchSeen = messageStore.size();

        // 逐条接入到全新环境
        setUp();
        List<IngestStatus> sequentialStatuses = new ArrayList<>();
        for (String raw : batch) {
            IngestResult r = service.ingest(raw);
            sequentialStatuses.add(r.status());
            log.info("sequential controlId={} status={} detail={}", r.messageControlId(), r.status(), r.detail());
        }
        var sequentialFinal = patientStore.find("P300^^^HIS").orElseThrow();

        // 最终患者状态完全一致
        assertThat(batchFinal.getName()).isEqualTo(sequentialFinal.getName()).isEqualTo("Final^Name");
        assertThat(batchFinal.getGender()).isEqualTo(sequentialFinal.getGender()).isEqualTo("F");
        assertThat(batchFinal.getLastUpdated()).isEqualTo(sequentialFinal.getLastUpdated());
        // 每条结论逐一一致
        assertThat(batchResult.results().stream().map(IngestResult::status).toList())
                .isEqualTo(sequentialStatuses)
                .containsExactly(IngestStatus.ACCEPTED_NEW, IngestStatus.IGNORED_STALE,
                        IngestStatus.DUPLICATE_RESENT, IngestStatus.IGNORED_STALE,
                        IngestStatus.CONFLICT_UPDATE, IngestStatus.REJECTED_ID_REUSE);
        // 冲突留痕数、已见编号数一致
        assertThat(batchConflicts).isEqualTo(conflictStore.size()).isEqualTo(1);
        assertThat(batchSeen).isEqualTo(messageStore.size()).isEqualTo(4);
    }

    @Test
    void batchInternalOrderDoesNotMatter_sameFinalState() {
        List<String> base = List.of(
                msg("B101", "20261002080000", "P301^^^HIS", "N^1", "M"),
                msg("B102", "20261002090000", "P301^^^HIS", "N^2", "F"),
                msg("B103", "20261002100000", "P301^^^HIS", "N^3", "M"),
                msg("B104", "20261002110000", "P301^^^HIS", "N^4", "F"));
        // 正序、反序、洗牌多种批次内顺序
        List<List<String>> orders = new ArrayList<>();
        orders.add(base);
        List<String> reversed = new ArrayList<>(base);
        Collections.reverse(reversed);
        orders.add(reversed);
        for (int seed = 1; seed <= 3; seed++) {
            List<String> shuffled = new ArrayList<>(base);
            Collections.shuffle(shuffled, new java.util.Random(seed));
            orders.add(shuffled);
        }
        String expectedName = null;
        for (List<String> order : orders) {
            setUp();
            log.info("=== batch internal order {} ===", order.stream()
                    .map(s -> s.contains("N^1") ? "B101" : s.contains("N^2") ? "B102"
                            : s.contains("N^3") ? "B103" : "B104")
                    .toList());
            BatchIngestResult r = service.ingestBatch(order);
            assertThat(r.accepted()).isTrue();
            var record = patientStore.find("P301^^^HIS").orElseThrow();
            if (expectedName == null) {
                expectedName = record.getName();
            }
            assertThat(record.getName()).isEqualTo(expectedName).isEqualTo("N^4");
            assertThat(record.getGender()).isEqualTo("F");
        }
    }

    @Test
    void batchExceedingCapacity_rejectedDeterministically_nothingProcessed() {
        service = newService(3); // 小容量便于验证
        List<String> batch = List.of(
                msg("B201", "20261002080000", "P302^^^HIS", "A^1", "M"),
                msg("B202", "20261002090000", "P302^^^HIS", "A^2", "M"),
                msg("B203", "20261002100000", "P302^^^HIS", "A^3", "M"),
                msg("B204", "20261002110000", "P302^^^HIS", "A^4", "M"));
        BatchIngestResult r = service.ingestBatch(batch);
        log.info("oversized batch accepted={} detail={}", r.accepted(), r.detail());
        assertThat(r.accepted()).isFalse();
        assertThat(r.results()).isEmpty();
        assertThat(r.maxBatchSize()).isEqualTo(3);
        assertThat(r.detail()).contains("4").contains("3");
        // 确定性的拒收：一条都没处理
        assertThat(messageStore.size()).isZero();
        assertThat(patientStore.size()).isZero();

        // 恰好在上限内：正常处理
        BatchIngestResult ok = service.ingestBatch(batch.subList(0, 3));
        assertThat(ok.accepted()).isTrue();
        assertThat(ok.results()).hasSize(3);
    }

    @Test
    void emptyBatch_rejectedDeterministically() {
        BatchIngestResult r = service.ingestBatch(List.of());
        assertThat(r.accepted()).isFalse();
        assertThat(r.results()).isEmpty();
        assertThat(messageStore.size()).isZero();
    }
}
