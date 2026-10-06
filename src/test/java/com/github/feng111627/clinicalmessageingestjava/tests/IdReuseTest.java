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

/** 重发区分：内容一致的重发 vs 编号被复用。 */
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
                new PatientMergeService(patientStore), new ConflictStore(), 100);
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
    void identicalResend_isDuplicate_notNew() {
        String raw = msg("R001", "20261002090000", "P200^^^HIS", "Zhang^San", "M");
        assertThat(ingestAndLog(raw).status()).isEqualTo(IngestStatus.ACCEPTED_NEW);
        assertThat(ingestAndLog(raw).status()).isEqualTo(IngestStatus.DUPLICATE_RESENT);
        // 真正的重发不被当成新消息：不产生新记录、不重复计数
        assertThat(messageStore.size()).isEqualTo(1);
        assertThat(patientStore.size()).isEqualTo(1);
    }

    @Test
    void reusedIdWithDifferentContent_isRejected_dataUntouched() {
        assertThat(ingestAndLog(msg("R011", "20261002090000", "P201^^^HIS", "Li^Si", "F")).status())
                .isEqualTo(IngestStatus.ACCEPTED_NEW);

        // 同一编号、内容对不上 -> 编号复用，区别于重发
        IngestResult reused = ingestAndLog(msg("R011", "20261002090000", "P201^^^HIS", "Li^Wu", "M"));
        assertThat(reused.status()).isEqualTo(IngestStatus.REJECTED_ID_REUSED);
        assertThat(reused.status()).isNotEqualTo(IngestStatus.DUPLICATE_RESENT);

        // 绝对不能动已入册数据
        var record = patientStore.find("P201^^^HIS").orElseThrow();
        assertThat(record.getName()).isEqualTo("Li^Si");
        assertThat(record.getGender()).isEqualTo("F");
        // 编号复用不重复计数：仍只有一条已处理消息
        assertThat(messageStore.size()).isEqualTo(1);
    }

    @Test
    void reusedIdWithDifferentPatient_neverCreatesRecord() {
        ingestAndLog(msg("R021", "20261002090000", "P202^^^HIS", "Wang^A", "M"));
        // 同编号但指向另一个患者 -> 编号复用拒收，不得新建患者档案
        assertThat(ingestAndLog(msg("R021", "20261002090000", "P299^^^HIS", "Wang^B", "F")).status())
                .isEqualTo(IngestStatus.REJECTED_ID_REUSED);
        assertThat(patientStore.find("P299^^^HIS")).isEmpty();
        assertThat(patientStore.size()).isEqualTo(1);
    }

    @Test
    void reusedIdRejection_isDeterministic_onRepeat() {
        ingestAndLog(msg("R031", "20261002090000", "P203^^^HIS", "Zhao^A", "M"));
        String tampered = msg("R031", "20261002090000", "P203^^^HIS", "Zhao^B", "M");
        assertThat(ingestAndLog(tampered).status()).isEqualTo(IngestStatus.REJECTED_ID_REUSED);
        // 再次投递同一条篡改消息，结论仍是编号复用（原编号内容指纹未变）
        assertThat(ingestAndLog(tampered).status()).isEqualTo(IngestStatus.REJECTED_ID_REUSED);
        // 原消息一字不差重发，仍是重发
        assertThat(ingestAndLog(msg("R031", "20261002090000", "P203^^^HIS", "Zhao^A", "M")).status())
                .isEqualTo(IngestStatus.DUPLICATE_RESENT);
    }

    // ---------- 本地查询：按消息编号 / 按患者 ----------

    @Test
    void queryByControlId_returnsConclusion() {
        ingestAndLog(msg("R041", "20261002090000", "P204^^^HIS", "Sun^A", "F"));
        MessageRecord record = messageStore.findByControlId("R041").orElseThrow();
        log.info("query controlId={} status={} detail={}", record.controlId(), record.status(), record.detail());
        assertThat(record.status()).isEqualTo(IngestStatus.ACCEPTED_NEW);
        assertThat(record.patientId()).isEqualTo("P204^^^HIS");
        assertThat(record.detail()).contains("已应用");
        assertThat(messageStore.findByControlId("NOPE")).isEmpty();
    }

    @Test
    void queryByPatientId_listsAllConclusions() {
        ingestAndLog(msg("R051", "20261002080000", "P205^^^HIS", "Qian^A", "M"));
        ingestAndLog(msg("R052", "20261002120000", "P205^^^HIS", "Qian^B", "F"));
        ingestAndLog(msg("R053", "20261002090000", "P206^^^HIS", "Other^P", "M")); // 另一个患者

        List<MessageRecord> records = messageStore.findByPatientId("P205^^^HIS");
        assertThat(records).hasSize(2);
        assertThat(records).extracting(MessageRecord::controlId)
                .containsExactlyInAnyOrder("R051", "R052");
        assertThat(records).extracting(MessageRecord::status)
                .containsOnly(IngestStatus.ACCEPTED_NEW);
    }

    @Test
    void rejectedMissingKeyField_conclusionAlsoQueryable() {
        ingestAndLog(msg("R061", "20261002090000", "P207^^^HIS", "", "M"));
        MessageRecord record = messageStore.findByControlId("R061").orElseThrow();
        assertThat(record.status()).isEqualTo(IngestStatus.REJECTED_MISSING_KEY_FIELD);
        // 按患者也能查到这条拒收结论，方便对账排查
        assertThat(messageStore.findByPatientId("P207^^^HIS")).hasSize(1);
    }
}
