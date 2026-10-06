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

/** 同一业务时间、内容对不上的更新冲突。 */
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
                new PatientMergeService(patientStore), conflictStore, 100);
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
    void sameTimeConflictingContent_judgedConflict_existingDataUntouched() {
        assertThat(ingestAndLog(msg("C001", "20261002090000", "P100^^^HIS", "Zhang^San", "M")).status())
                .isEqualTo(IngestStatus.ACCEPTED_NEW);

        // 同一业务时间、姓名对不上
        IngestResult conflict = ingestAndLog(msg("C002", "20261002090000", "P100^^^HIS", "Zhang^Si", "M"));
        assertThat(conflict.status()).isEqualTo(IngestStatus.CONFLICT_UPDATE);

        // 已有数据保持不动
        var record = patientStore.find("P100^^^HIS").orElseThrow();
        assertThat(record.getName()).isEqualTo("Zhang^San");
        assertThat(record.getGender()).isEqualTo("M");
    }

    @Test
    void conflictTrace_isRecordedAndQueryable() {
        ingestAndLog(msg("C011", "20261002090000", "P101^^^HIS", "Li^Si", "F"));
        ingestAndLog(msg("C012", "20261002090000", "P101^^^HIS", "Li^Wu", "M"));

        List<ConflictRecord> traces = conflictStore.findByPatientId("P101^^^HIS");
        assertThat(traces).hasSize(1);
        ConflictRecord trace = traces.get(0);
        log.info("conflict trace existing={} incoming={} fields={} detail={}",
                trace.existingControlId(), trace.incomingControlId(), trace.differingFields(), trace.detail());
        // 哪条跟哪条冲突
        assertThat(trace.existingControlId()).isEqualTo("C011");
        assertThat(trace.incomingControlId()).isEqualTo("C012");
        // 哪些字段对不上（姓名、性别都变了）
        assertThat(trace.differingFields()).containsExactlyInAnyOrder("PID-5", "PID-8");
        // 判断依据
        assertThat(trace.detail()).contains("更新冲突").contains("2026-10-02T09:00");
    }

    @Test
    void conflictDoesNotBlockPatient_newerUpdateStillApplies() {
        ingestAndLog(msg("C021", "20261002090000", "P102^^^HIS", "Wang^A", "M"));
        assertThat(ingestAndLog(msg("C022", "20261002090000", "P102^^^HIS", "Wang^B", "F")).status())
                .isEqualTo(IngestStatus.CONFLICT_UPDATE);

        // 业务时间更靠后的正常更新照常生效
        assertThat(ingestAndLog(msg("C023", "20261002100000", "P102^^^HIS", "Wang^C", "F")).status())
                .isEqualTo(IngestStatus.ACCEPTED_NEW);
        var record = patientStore.find("P102^^^HIS").orElseThrow();
        assertThat(record.getName()).isEqualTo("Wang^C");
        assertThat(record.getGender()).isEqualTo("F");
    }

    @Test
    void sameTimeIdenticalContent_isNotConflict() {
        ingestAndLog(msg("C031", "20261002090000", "P103^^^HIS", "Zhao^Liu", "M"));
        // 不同编号、同一业务时间、内容一致 -> 正常应用，不算冲突
        assertThat(ingestAndLog(msg("C032", "20261002090000", "P103^^^HIS", "Zhao^Liu", "M")).status())
                .isEqualTo(IngestStatus.ACCEPTED_NEW);
        assertThat(conflictStore.size()).isZero();
    }

    @Test
    void conflictingMessage_conclusionQueryableByControlId() {
        ingestAndLog(msg("C041", "20261002090000", "P104^^^HIS", "Sun^Qi", "F"));
        ingestAndLog(msg("C042", "20261002090000", "P104^^^HIS", "Sun^Ba", "F"));

        var record = messageStore.findByControlId("C042").orElseThrow();
        assertThat(record.status()).isEqualTo(IngestStatus.CONFLICT_UPDATE);
        assertThat(record.patientId()).isEqualTo("P104^^^HIS");
        assertThat(record.detail()).contains("C041"); // 指向与之冲突的已有消息
    }

    // ---------- 关键患者字段为空：独立拒收原因 ----------

    @Test
    void blankName_rejectedWithDistinctReason_notSilentlyStored() {
        // PID-5 为空
        IngestResult r = ingestAndLog(msg("C051", "20261002090000", "P105^^^HIS", "", "M"));
        assertThat(r.status()).isEqualTo(IngestStatus.REJECTED_MISSING_KEY_FIELD);
        // 与解析失败、更新冲突都能区分开
        assertThat(r.status()).isNotEqualTo(IngestStatus.REJECTED_PARSE);
        assertThat(r.status()).isNotEqualTo(IngestStatus.CONFLICT_UPDATE);
        // 不写入患者档案，不污染下游对账
        assertThat(patientStore.find("P105^^^HIS")).isEmpty();
        assertThat(patientStore.size()).isZero();
    }

    @Test
    void blankName_doesNotOverwriteExistingRecord() {
        ingestAndLog(msg("C061", "20261002090000", "P106^^^HIS", "Zhou^Jiu", "M"));
        // 补传消息业务时间更新但姓名为空 -> 拒收，不得覆盖已有档案
        assertThat(ingestAndLog(msg("C062", "20261002100000", "P106^^^HIS", "", "F")).status())
                .isEqualTo(IngestStatus.REJECTED_MISSING_KEY_FIELD);
        var record = patientStore.find("P106^^^HIS").orElseThrow();
        assertThat(record.getName()).isEqualTo("Zhou^Jiu");
        assertThat(record.getGender()).isEqualTo("M");
    }
}
