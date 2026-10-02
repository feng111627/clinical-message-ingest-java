package com.github.feng111627.clinicalmessageingestjava.tests;

import com.github.feng111627.clinicalmessageingestjava.hl7.Hl7Parser;
import com.github.feng111627.clinicalmessageingestjava.hl7.ParseFailureReason;
import com.github.feng111627.clinicalmessageingestjava.ingest.IngestResult;
import com.github.feng111627.clinicalmessageingestjava.ingest.IngestStatus;
import com.github.feng111627.clinicalmessageingestjava.ingest.MessageIngestService;
import com.github.feng111627.clinicalmessageingestjava.merge.PatientMergeService;
import com.github.feng111627.clinicalmessageingestjava.store.MessageStore;
import com.github.feng111627.clinicalmessageingestjava.store.PatientStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessageIngestServiceTest {

    private static final Logger log = LoggerFactory.getLogger(MessageIngestServiceTest.class);

    private MessageStore messageStore;
    private PatientStore patientStore;
    private MessageIngestService service;

    @BeforeEach
    void setUp() {
        messageStore = new MessageStore();
        patientStore = new PatientStore();
        service = new MessageIngestService(new Hl7Parser(), messageStore,
                new PatientMergeService(patientStore));
    }

    /** 构造一条 ADT^A01 消息。 */
    private String msg(String controlId, String msh7, String patientId, String name, String gender) {
        return "MSH|^~\\&|HIS|HOSPITAL|RCV|HOSPITAL|" + msh7 + "||ADT^A01|" + controlId + "|P|2.5\r"
                + "PID|1||" + patientId + "||" + name + "||19800101|" + gender;
    }

    private IngestResult ingestAndLog(String raw) {
        IngestResult r = service.ingest(raw);
        log.info("assert controlId={} status={} parseFailure={} detail={}",
                r.messageControlId(), r.status(), r.parseFailureReason(), r.detail());
        return r;
    }

    // ---------- 重复投递 ----------

    @Test
    void duplicateDelivery_isIdempotent() {
        String raw = msg("MSG001", "20261002090000", "P001^^^HIS", "Zhang^San", "M");

        IngestResult first = ingestAndLog(raw);
        IngestResult second = ingestAndLog(raw);
        IngestResult third = ingestAndLog(raw);

        assertThat(first.status()).isEqualTo(IngestStatus.ACCEPTED_NEW);
        assertThat(second.status()).isEqualTo(IngestStatus.DUPLICATE_RESENT);
        assertThat(third.status()).isEqualTo(IngestStatus.DUPLICATE_RESENT);
        // 不产生重复患者记录
        assertThat(patientStore.size()).isEqualTo(1);
        assertThat(messageStore.size()).isEqualTo(1);
        // 重发与新消息可区分
        assertThat(second.status()).isNotEqualTo(first.status());
    }

    // ---------- 乱序到达 ----------

    @Test
    void outOfOrder_oldDataNeverOverwritesNewer() {
        // 新数据先到
        assertThat(ingestAndLog(msg("MSG101", "20261002120000", "P002^^^HIS", "Li^Si", "F")).status())
                .isEqualTo(IngestStatus.ACCEPTED_NEW);
        // 旧数据补传后到：应被忽略
        IngestResult stale = ingestAndLog(msg("MSG102", "20261002080000", "P002^^^HIS", "Li^OLD", "M"));
        assertThat(stale.status()).isEqualTo(IngestStatus.IGNORED_STALE);

        var record = patientStore.find("P002^^^HIS").orElseThrow();
        assertThat(record.getName()).isEqualTo("Li^Si");
        assertThat(record.getGender()).isEqualTo("F");
    }

    @Test
    void outOfOrder_lateArrivingNewerDataStillApplies() {
        // 旧数据先到
        assertThat(ingestAndLog(msg("MSG201", "20261002080000", "P003^^^HIS", "Wang^Old", "M")).status())
                .isEqualTo(IngestStatus.ACCEPTED_NEW);
        // 断线补传的更新数据后到：应覆盖
        assertThat(ingestAndLog(msg("MSG202", "20261002150000", "P003^^^HIS", "Wang^New", "F")).status())
                .isEqualTo(IngestStatus.ACCEPTED_NEW);

        var record = patientStore.find("P003^^^HIS").orElseThrow();
        assertThat(record.getName()).isEqualTo("Wang^New");
        assertThat(record.getGender()).isEqualTo("F");
    }

    @Test
    void sameBatch_differentArrivalOrders_sameFinalState() {
        List<String> batch = List.of(
                msg("MSG301", "20261002080000", "P004^^^HIS", "A^One", "M"),
                msg("MSG302", "20261002100000", "P004^^^HIS", "A^Two", "F"),
                msg("MSG303", "20261002120000", "P004^^^HIS", "A^Three", "M"));

        // 正序
        batch.forEach(this::ingestAndLog);
        var forward = patientStore.find("P004^^^HIS").orElseThrow();

        // 反序重放到全新环境
        setUp();
        for (int i = batch.size() - 1; i >= 0; i--) {
            ingestAndLog(batch.get(i));
        }
        var backward = patientStore.find("P004^^^HIS").orElseThrow();

        assertThat(backward.getName()).isEqualTo(forward.getName()).isEqualTo("A^Three");
        assertThat(backward.getGender()).isEqualTo(forward.getGender()).isEqualTo("M");
        assertThat(backward.getLastUpdated()).isEqualTo(forward.getLastUpdated());
    }

    // ---------- 畸形消息：按可区分原因拒收 ----------

    @Test
    void malformed_emptyMessage() {
        IngestResult r = ingestAndLog("   ");
        assertThat(r.status()).isEqualTo(IngestStatus.REJECTED_PARSE);
        assertThat(r.parseFailureReason()).isEqualTo(ParseFailureReason.EMPTY_MESSAGE);
    }

    @Test
    void malformed_missingMsh() {
        IngestResult r = ingestAndLog("PID|1||P001^^^HIS||Zhang^San");
        assertThat(r.status()).isEqualTo(IngestStatus.REJECTED_PARSE);
        assertThat(r.parseFailureReason()).isEqualTo(ParseFailureReason.MISSING_MSH_SEGMENT);
    }

    @Test
    void malformed_badSegmentStructure() {
        IngestResult r = ingestAndLog(
                "MSH|^~\\&|HIS|HOSPITAL|RCV|HOSPITAL|20261002090000||ADT^A01|MSG401|P|2.5\r"
                        + "BADSEGMENTWITHOUTSEPARATOR\r"
                        + "PID|1||P001^^^HIS||Zhang^San");
        assertThat(r.status()).isEqualTo(IngestStatus.REJECTED_PARSE);
        assertThat(r.parseFailureReason()).isEqualTo(ParseFailureReason.BAD_SEGMENT_STRUCTURE);
    }

    @Test
    void malformed_missingRequiredField() {
        // 缺 MSH-10 （消息控制标识）
        IngestResult r = ingestAndLog(
                "MSH|^~\\&|HIS|HOSPITAL|RCV|HOSPITAL|20261002090000||ADT^A01||P|2.5\r"
                        + "PID|1||P001^^^HIS||Zhang^San");
        assertThat(r.status()).isEqualTo(IngestStatus.REJECTED_PARSE);
        assertThat(r.parseFailureReason()).isEqualTo(ParseFailureReason.MISSING_REQUIRED_FIELD);
    }

    @Test
    void malformed_invalidTimestamp() {
        IngestResult r = ingestAndLog(msg("MSG405", "2026-10-02 09:00", "P001^^^HIS", "Zhang^San", "M"));
        assertThat(r.status()).isEqualTo(IngestStatus.REJECTED_PARSE);
        assertThat(r.parseFailureReason()).isEqualTo(ParseFailureReason.INVALID_TIMESTAMP);
    }

    @Test
    void malformed_messagesNeverEnterStores() {
        ingestAndLog("");
        ingestAndLog("PID|1||P001^^^HIS||Zhang^San");
        ingestAndLog(msg("MSG406", "not-a-time", "P001^^^HIS", "Zhang^San", "M"));
        assertThat(messageStore.size()).isZero();
        assertThat(patientStore.size()).isZero();
    }

    // ---------- 患者标识冲突 ----------

    @Test
    void patientIdConflict_isRejected() {
        assertThat(ingestAndLog(msg("MSG501", "20261002090000", "P009^^^HIS", "Zhang^San", "M")).status())
                .isEqualTo(IngestStatus.ACCEPTED_NEW);
        // 同一本地病案号、不同分配机构 -> 冲突
        IngestResult conflict = ingestAndLog(msg("MSG502", "20261002100000", "P009^^^LIS", "Zhang^San", "M"));
        assertThat(conflict.status()).isEqualTo(IngestStatus.REJECTED_CONFLICT);
        // 冲突消息不得覆盖原记录
        assertThat(patientStore.size()).isEqualTo(1);
        assertThat(patientStore.find("P009^^^HIS")).isPresent();
    }

    // ---------- 转义字符 ----------

    @Test
    void escapeSequences_areUnescaped() {
        IngestResult r = ingestAndLog(msg("MSG601", "20261002090000", "P010^^^HIS", "Escaped\\F\\Name", "M"));
        assertThat(r.status()).isEqualTo(IngestStatus.ACCEPTED_NEW);
        assertThat(patientStore.find("P010^^^HIS").orElseThrow().getName()).isEqualTo("Escaped|Name");
    }
}
