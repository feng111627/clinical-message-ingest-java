package com.github.feng111627.clinicalmessageingestjava.tests;

import com.github.feng111627.clinicalmessageingestjava.hl7.Hl7Parser;
import com.github.feng111627.clinicalmessageingestjava.ingest.IngestResult;
import com.github.feng111627.clinicalmessageingestjava.ingest.IngestStatus;
import com.github.feng111627.clinicalmessageingestjava.ingest.MessageIngestService;
import com.github.feng111627.clinicalmessageingestjava.merge.PatientMergeService;
import com.github.feng111627.clinicalmessageingestjava.store.ConflictStore;
import com.github.feng111627.clinicalmessageingestjava.store.MessageRecord;
import com.github.feng111627.clinicalmessageingestjava.store.MessageStore;
import com.github.feng111627.clinicalmessageingestjava.store.PatientStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 重发细分：一字不差的重发 vs 编号被复用但内容对不上；处理结论本地可查。 */
class IdReuseTest {

    private static final Logger log = LoggerFactory.getLogger(IdReuseTest.class);

    private MessageStore messageStore;
    private PatientStore patientStore;
    private MessageIngestService service;

    @BeforeEach
    void setUp() {
        messageStore = new MessageStore();
        patientStore = new PatientStore();
        service = new MessageIngestService(new Hl7Parser(), messageStore,
                new PatientMergeService(patientStore), new ConflictStore(),
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
    void sameIdIdenticalContent_isDuplicateResent() {
        String raw = msg("R001", "20261002090000", "P200^^^HIS", "Zhang^San", "M");
        assertThat(ingestAndLog(raw).status()).isEqualTo(IngestStatus.ACCEPTED_NEW);
        assertThat(ingestAndLog(raw).status()).isEqualTo(IngestStatus.DUPLICATE_RESENT);
        assertThat(messageStore.size()).isEqualTo(1);
        assertThat(patientStore.size()).isEqualTo(1);
    }

    @Test
    void sameIdDifferentContent_isIdReuse_neverTouchesEnrolledData() {
        assertThat(ingestAndLog(msg("R101", "20261002090000", "P201^^^HIS", "Li^Si", "F")).status())
                .isEqualTo(IngestStatus.ACCEPTED_NEW);

        // 上游用同一编号发来内容不同的消息
        IngestResult reuse = ingestAndLog(msg("R101", "20261002100000", "P201^^^HIS", "Li^HACK", "M"));
        assertThat(reuse.status()).isEqualTo(IngestStatus.REJECTED_ID_REUSE);
        // 与一字不差的重发、解析失败均可区分
        assertThat(reuse.status()).isNotIn(IngestStatus.DUPLICATE_RESENT, IngestStatus.REJECTED_PARSE);

        // 已入册数据分毫不动
        var record = patientStore.find("P201^^^HIS").orElseThrow();
        assertThat(record.getName()).isEqualTo("Li^Si");
        assertThat(record.getGender()).isEqualTo("F");
        assertThat(record.getLastAppliedControlId()).isEqualTo("R101");
        // 编号复用不算新消息：不重复计数
        assertThat(messageStore.size()).isEqualTo(1);
        assertThat(patientStore.size()).isEqualTo(1);
    }

    @Test
    void afterIdReuse_trueResendOfOriginal_isStillDuplicateNotNew() {
        String original = msg("R201", "20261002090000", "P202^^^HIS", "Wang^Wu", "M");
        ingestAndLog(original);
        ingestAndLog(msg("R201", "20261002150000", "P202^^^HIS", "Wang^OTHER", "F")); // 编号复用

        // 原始消息的一字不差重发，仍判重发，绝不能被当成新消息
        assertThat(ingestAndLog(original).status()).isEqualTo(IngestStatus.DUPLICATE_RESENT);
        var record = patientStore.find("P202^^^HIS").orElseThrow();
        assertThat(record.getName()).isEqualTo("Wang^Wu");
        assertThat(messageStore.size()).isEqualTo(1);
    }

    @Test
    void afterIdReuse_newMessageWithFreshId_stillProcessed() {
        ingestAndLog(msg("R301", "20261002090000", "P203^^^HIS", "Zhao^Liu", "F"));
        // 编号复用（编号相同、内容不同）
        assertThat(ingestAndLog(msg("R301", "20261002093000", "P203^^^HIS", "Bad^Data", "M")).status())
                .isEqualTo(IngestStatus.REJECTED_ID_REUSE);
        // 后续正常的新编号消息不受影响
        assertThat(ingestAndLog(msg("R302", "20261002100000", "P203^^^HIS", "Zhao^New", "F")).status())
                .isEqualTo(IngestStatus.ACCEPTED_NEW);
        assertThat(patientStore.find("P203^^^HIS").orElseThrow().getName()).isEqualTo("Zhao^New");
    }

    @Test
    void conclusionsAreQueryableByControlIdAndPatient() {
        ingestAndLog(msg("R401", "20261002090000", "P204^^^HIS", "Qian^Qi", "M"));
        ingestAndLog(msg("R401", "20261002110000", "P204^^^HIS", "Qian^OTHER", "F")); // 编号复用
        ingestAndLog(msg("R402", "20261002120000", "P204^^^HIS", "Qian^New", "M"));

        // 按消息编号查：同一编号的原始结论与复用拒收都查得到
        List<MessageRecord> r401 = messageStore.findByControlId("R401");
        assertThat(r401).hasSize(2);
        assertThat(r401.stream().map(MessageRecord::status))
                .containsExactly(IngestStatus.ACCEPTED_NEW, IngestStatus.REJECTED_ID_REUSE);
        assertThat(r401.get(0).fingerprint()).isNotEqualTo(r401.get(1).fingerprint());
        log.info("query R401 -> {}", r401);

        // 按患者查：该患者的全部处理结论
        List<MessageRecord> byPatient = messageStore.findByPatient("P204^^^HIS");
        assertThat(byPatient.stream().map(MessageRecord::messageControlId))
                .containsExactly("R401", "R401", "R402");
        assertThat(byPatient.stream().map(MessageRecord::status))
                .containsExactly(IngestStatus.ACCEPTED_NEW, IngestStatus.REJECTED_ID_REUSE,
                        IngestStatus.ACCEPTED_NEW);
        log.info("query P204 -> {}", byPatient);

        // 没见过的编号/患者查不到
        assertThat(messageStore.findByControlId("NOPE")).isEmpty();
        assertThat(messageStore.findByPatient("P999^^^HIS")).isEmpty();
    }
}
