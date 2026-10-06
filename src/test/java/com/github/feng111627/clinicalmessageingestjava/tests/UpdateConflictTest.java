package com.github.feng111627.clinicalmessageingestjava.tests;

import com.github.feng111627.clinicalmessageingestjava.hl7.Hl7Parser;
import com.github.feng111627.clinicalmessageingestjava.ingest.IngestResult;
import com.github.feng111627.clinicalmessageingestjava.ingest.IngestStatus;
import com.github.feng111627.clinicalmessageingestjava.ingest.MessageIngestService;
import com.github.feng111627.clinicalmessageingestjava.merge.PatientMergeService;
import com.github.feng111627.clinicalmessageingestjava.store.ConflictRecord;
import com.github.feng111627.clinicalmessageingestjava.store.ConflictStore;
import com.github.feng111627.clinicalmessageingestjava.store.MessageStore;
import com.github.feng111627.clinicalmessageingestjava.store.PatientStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 同一业务时间、内容对不上的更新冲突，以及关键字段为空拒收。 */
class UpdateConflictTest {

    private static final Logger log = LoggerFactory.getLogger(UpdateConflictTest.class);

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

    private IngestResult ingestAndLog(String raw) {
        IngestResult r = service.ingest(raw);
        log.info("assert controlId={} status={} detail={}", r.messageControlId(), r.status(), r.detail());
        return r;
    }

    @Test
    void sameTimeContentMismatch_isConflict_existingDataUntouched_andTraced() {
        assertThat(ingestAndLog(msg("C001", "20261002090000", "P100^^^HIS", "Zhang^San", "M")).status())
                .isEqualTo(IngestStatus.ACCEPTED_NEW);

        // 同一业务时间、姓名对不上
        IngestResult conflict = ingestAndLog(msg("C002", "20261002090000", "P100^^^HIS", "Zhang^Si", "M"));
        assertThat(conflict.status()).isEqualTo(IngestStatus.CONFLICT_UPDATE);
        // 与既有结论可区分
        assertThat(conflict.status()).isNotIn(IngestStatus.REJECTED_PARSE,
                IngestStatus.REJECTED_CONFLICT, IngestStatus.REJECTED_VALIDATION);

        // 已有数据保持不动
        var record = patientStore.find("P100^^^HIS").orElseThrow();
        assertThat(record.getName()).isEqualTo("Zhang^San");
        assertThat(record.getLastAppliedControlId()).isEqualTo("C001");

        // 留痕：哪条跟哪条冲突、哪个字段对不上、判断依据
        assertThat(conflictStore.size()).isEqualTo(1);
        ConflictRecord trace = conflictStore.findByPatient("P100^^^HIS").get(0);
        log.info("conflict trace seq={} incoming={} existing={} basis={}",
                trace.sequence(), trace.incomingControlId(), trace.existingControlId(), trace.basis());
        assertThat(trace.incomingControlId()).isEqualTo("C002");
        assertThat(trace.existingControlId()).isEqualTo("C001");
        assertThat(trace.mismatches()).hasSize(1);
        assertThat(trace.mismatches().get(0).field()).contains("PID-5");
        assertThat(trace.mismatches().get(0).existingValue()).isEqualTo("Zhang^San");
        assertThat(trace.mismatches().get(0).incomingValue()).isEqualTo("Zhang^Si");
        assertThat(trace.basis()).contains("Zhang^San").contains("Zhang^Si");
        // 按消息编号也能查到同一条痕迹
        assertThat(conflictStore.findByControlId("C002")).containsExactly(trace);
        assertThat(conflictStore.findByControlId("C001")).containsExactly(trace);
    }

    @Test
    void conflictDoesNotBlockPatient_laterNewerUpdateStillApplies() {
        ingestAndLog(msg("C101", "20261002090000", "P101^^^HIS", "Li^Si", "F"));
        assertThat(ingestAndLog(msg("C102", "20261002090000", "P101^^^HIS", "Li^Wu", "F")).status())
                .isEqualTo(IngestStatus.CONFLICT_UPDATE);

        // 业务时间更靠后的正常更新仍然生效
        assertThat(ingestAndLog(msg("C103", "20261002100000", "P101^^^HIS", "Li^New", "M")).status())
                .isEqualTo(IngestStatus.ACCEPTED_NEW);
        var record = patientStore.find("P101^^^HIS").orElseThrow();
        assertThat(record.getName()).isEqualTo("Li^New");
        assertThat(record.getGender()).isEqualTo("M");
    }

    @Test
    void sameTimeIdenticalContent_isAppliedNotConflict() {
        // 不同编号、同一业务时间、内容一致：幂等应用，不算冲突
        assertThat(ingestAndLog(msg("C201", "20261002090000", "P102^^^HIS", "Wang^Wu", "M")).status())
                .isEqualTo(IngestStatus.ACCEPTED_NEW);
        assertThat(ingestAndLog(msg("C202", "20261002090000", "P102^^^HIS", "Wang^Wu", "M")).status())
                .isEqualTo(IngestStatus.ACCEPTED_NEW);
        assertThat(conflictStore.size()).isZero();
    }

    @Test
    void sameTimeConflict_allArrivalOrders_consistentExplainableOutcome() {
        // A、B 同一时间内容对不上，C 时间更靠后：枚举到达顺序
        List<List<String>> orders = List.of(
                List.of("A", "B", "C"),
                List.of("B", "A", "C"),
                List.of("A", "C", "B"),
                List.of("B", "C", "A"),
                List.of("C", "A", "B"),
                List.of("C", "B", "A"));
        for (List<String> order : orders) {
            setUp();
            log.info("=== arrival order {} ===", order);
            for (String tag : order) {
                switch (tag) {
                    case "A" -> ingestAndLog(msg("CA", "20261002090000", "P103^^^HIS", "Name^A", "M"));
                    case "B" -> ingestAndLog(msg("CB", "20261002090000", "P103^^^HIS", "Name^B", "F"));
                    case "C" -> ingestAndLog(msg("CC", "20261002120000", "P103^^^HIS", "Name^C", "M"));
                }
            }
            var record = patientStore.find("P103^^^HIS").orElseThrow();
            // 最终状态与到达顺序无关：C 业务时间最靠后，必然生效
            assertThat(record.getName()).isEqualTo("Name^C");
            boolean bothBeforeC = order.indexOf("A") < order.indexOf("C")
                    && order.indexOf("B") < order.indexOf("C");
            if (bothBeforeC) {
                // A/B 在 C 之前相继到达：先到先得，后到者冲突留痕
                assertThat(conflictStore.size()).isEqualTo(1);
                ConflictRecord trace = conflictStore.all().get(0);
                String first = order.get(0);
                assertThat(trace.existingControlId()).isEqualTo("C" + first);
                assertThat(trace.incomingControlId()).isEqualTo("C" + (first.equals("A") ? "B" : "A"));
            } else {
                // C 先占位（或夹在 A/B 之间）：其余同时间消息是更旧数据，被忽略而非冲突
                assertThat(conflictStore.size()).isZero();
            }
        }
    }

    // ---------- 关键患者字段为空：按可区分原因拒收 ----------

    @Test
    void blankName_rejectedWithDistinctReason_neverCreatesBlankProfile() {
        IngestResult r = ingestAndLog(msg("C301", "20261002090000", "P104^^^HIS", "", "M"));
        assertThat(r.status()).isEqualTo(IngestStatus.REJECTED_VALIDATION);
        // 与解析失败、更新冲突可区分
        assertThat(r.status()).isNotIn(IngestStatus.REJECTED_PARSE, IngestStatus.CONFLICT_UPDATE);
        assertThat(r.parseFailureReason()).isNull();
        // 不污染患者档案：不产生空记录
        assertThat(patientStore.size()).isZero();
        assertThat(patientStore.find("P104^^^HIS")).isEmpty();
    }

    @Test
    void blankName_doesNotOverwriteExistingProfile() {
        ingestAndLog(msg("C401", "20261002090000", "P105^^^HIS", "Zhao^Liu", "F"));
        assertThat(ingestAndLog(msg("C402", "20261002100000", "P105^^^HIS", "", "M")).status())
                .isEqualTo(IngestStatus.REJECTED_VALIDATION);
        var record = patientStore.find("P105^^^HIS").orElseThrow();
        assertThat(record.getName()).isEqualTo("Zhao^Liu");
        assertThat(record.getGender()).isEqualTo("F");
    }

    @Test
    void blankName_rejectionIsQueryableByPatientAndControlId() {
        ingestAndLog(msg("C501", "20261002090000", "P106^^^HIS", "", "M"));
        var byControlId = messageStore.findByControlId("C501");
        assertThat(byControlId).hasSize(1);
        assertThat(byControlId.get(0).status()).isEqualTo(IngestStatus.REJECTED_VALIDATION);
        var byPatient = messageStore.findByPatient("P106^^^HIS");
        assertThat(byPatient).hasSize(1);
        assertThat(byPatient.get(0).messageControlId()).isEqualTo("C501");
    }
}
