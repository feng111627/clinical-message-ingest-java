package com.github.feng111627.clinicalmessageingestjava.tests;

import com.github.feng111627.clinicalmessageingestjava.hl7.Hl7Parser;
import com.github.feng111627.clinicalmessageingestjava.hl7.RejectReason;
import com.github.feng111627.clinicalmessageingestjava.ingest.IngestResult;
import com.github.feng111627.clinicalmessageingestjava.ingest.IngestService;
import com.github.feng111627.clinicalmessageingestjava.ingest.IngestStatus;
import com.github.feng111627.clinicalmessageingestjava.ingest.MessageJournal;
import com.github.feng111627.clinicalmessageingestjava.ingest.PatientStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 接入服务场景测试：重复投递、乱序到达、畸形消息、冲突链路。
 * 每个用例打印消息标识与判定依据。
 */
class IngestServiceTest {

    private static final Logger log = LoggerFactory.getLogger(IngestServiceTest.class);

    private IngestService service;
    private PatientStore store;

    @BeforeEach
    void setUp() {
        store = new PatientStore();
        service = new IngestService(new Hl7Parser(), store, new MessageJournal());
    }

    /** 构造一条 ADT 消息：controlId / patientId / name / 业务时间(EVN-2) 可变。 */
    private static String msg(String controlId, String patientId, String name, String evnTime) {
        return "MSH|^~\\&|HIS|HOSPITAL|RCV|HOSPITAL|20240101120000||ADT^A01|" + controlId + "|P|2.5\r"
                + "EVN|A01|" + evnTime + "\r"
                + "PID|1||" + patientId + "^^^HIS||" + name;
    }

    private IngestResult ingestAndLog(String controlId, String raw) {
        IngestResult r = service.ingest(raw);
        log.info("判定: controlId={} status={} reason={} 依据={}",
                controlId, r.status(), r.rejectReason(), r.detail());
        return r;
    }

    // ---------- 重复投递 ----------

    @Test
    void duplicateDeliveryIsIdempotent() {
        String raw = msg("MSG001", "P1", "Zhang^San", "20240101100000");
        assertEquals(IngestStatus.ACCEPTED_NEW, ingestAndLog("MSG001", raw).status());
        // 原样重发两次
        assertEquals(IngestStatus.DUPLICATE, ingestAndLog("MSG001", raw).status());
        assertEquals(IngestStatus.DUPLICATE, ingestAndLog("MSG001", raw).status());
        // 不产生重复患者记录
        assertEquals(1, store.count());
        assertEquals("Zhang^San", store.findByPatientId("P1").orElseThrow().getPatientName());
    }

    @Test
    void sameControlIdWithDifferentContentIsConflict() {
        assertEquals(IngestStatus.ACCEPTED_NEW,
                ingestAndLog("MSG002", msg("MSG002", "P1", "Zhang^San", "20240101100000")).status());
        // 同一消息控制标识，但患者标识不同 —— 患者标识冲突
        IngestResult r = ingestAndLog("MSG002", msg("MSG002", "P2", "Li^Si", "20240101100000"));
        assertEquals(IngestStatus.CONFLICT_REPLAY, r.status());
        assertEquals(1, store.count());
    }

    // ---------- 乱序到达 ----------

    @Test
    void outOfOrderDeliveryKeepsNewestData() {
        // 新数据先到
        assertEquals(IngestStatus.ACCEPTED_NEW,
                ingestAndLog("M-NEW", msg("M-NEW", "P1", "Zhang^SanNew", "20240102100000")).status());
        // 旧数据补传后到，不得覆盖
        IngestResult stale = ingestAndLog("M-OLD", msg("M-OLD", "P1", "Zhang^SanOld", "20240101100000"));
        assertEquals(IngestStatus.ACCEPTED_STALE, stale.status());
        assertEquals("Zhang^SanNew", store.findByPatientId("P1").orElseThrow().getPatientName());
    }

    @Test
    void inOrderDeliveryMerges() {
        assertEquals(IngestStatus.ACCEPTED_NEW,
                ingestAndLog("M1", msg("M1", "P1", "Name^V1", "20240101100000")).status());
        assertEquals(IngestStatus.ACCEPTED_MERGED,
                ingestAndLog("M2", msg("M2", "P1", "Name^V2", "20240102100000")).status());
        assertEquals("Name^V2", store.findByPatientId("P1").orElseThrow().getPatientName());
    }

    @Test
    void anyArrivalOrderYieldsSameFinalState() {
        // 同一批三条消息（含一条重复投递），枚举全部到达顺序
        List<String[]> batch = List.of(
                new String[]{"A1", msg("A1", "P1", "Name^V1", "20240101100000")},
                new String[]{"A2", msg("A2", "P1", "Name^V2", "20240102100000")},
                new String[]{"A3", msg("A3", "P1", "Name^V3", "20240103100000")},
                new String[]{"A2-dup", msg("A2", "P1", "Name^V2", "20240102100000")} // 重复投递
        );
        List<List<String[]>> orders = new ArrayList<>();
        permute(batch, 0, orders);

        for (List<String[]> order : orders) {
            PatientStore s = new PatientStore();
            IngestService svc = new IngestService(new Hl7Parser(), s, new MessageJournal());
            for (String[] item : order) {
                IngestResult r = svc.ingest(item[1]);
                log.info("permutation判定: controlId={} status={} 依据={}", item[0], r.status(), r.detail());
            }
            assertEquals(1, s.count(), "患者记录数应与到达顺序无关");
            var rec = s.findByPatientId("P1").orElseThrow();
            assertEquals("Name^V3", rec.getPatientName(), "最终状态应与到达顺序无关");
            assertEquals("A3", rec.getLastMessageControlId());
        }
        log.info("全部 {} 种到达顺序的最终状态一致", orders.size());
    }

    private static void permute(List<String[]> arr, int i, List<List<String[]>> out) {
        if (i == arr.size()) {
            out.add(new ArrayList<>(arr));
            return;
        }
        for (int j = i; j < arr.size(); j++) {
            List<String[]> copy = new ArrayList<>(arr);
            var tmp = copy.get(i);
            copy.set(i, copy.get(j));
            copy.set(j, tmp);
            permute(copy, i + 1, out);
        }
    }

    // ---------- 更新冲突 ----------

    @Test
    void sameEventTimeWithDifferentContentIsConflict() {
        assertEquals(IngestStatus.ACCEPTED_NEW,
                ingestAndLog("C1", msg("C1", "P1", "Zhang^San", "20240101100000")).status());
        IngestResult r = ingestAndLog("C2", msg("C2", "P1", "Zhang^Si", "20240101100000"));
        assertEquals(IngestStatus.CONFLICT_UPDATE, r.status());
        // 冲突不应用，原数据保留
        assertEquals("Zhang^San", store.findByPatientId("P1").orElseThrow().getPatientName());
    }

    // ---------- 畸形消息：拒收原因可区分 ----------

    @Test
    void missingControlIdRejected() {
        String raw = "MSH|^~\\&|HIS|HOSPITAL|RCV|HOSPITAL|20240101120000||ADT^A01||P|2.5\r"
                + "PID|1||P1^^^HIS||Zhang^San";
        IngestResult r = ingestAndLog("(无MSH-10)", raw);
        assertEquals(IngestStatus.REJECTED, r.status());
        assertEquals(RejectReason.MISSING_FIELD, r.rejectReason());
        assertEquals(0, store.count());
    }

    @Test
    void missingPatientIdRejected() {
        String raw = "MSH|^~\\&|HIS|HOSPITAL|RCV|HOSPITAL|20240101120000||ADT^A01|MSG010|P|2.5\r"
                + "PID|1";
        IngestResult r = ingestAndLog("MSG010", raw);
        assertEquals(IngestStatus.REJECTED, r.status());
        assertEquals(RejectReason.MISSING_FIELD, r.rejectReason());
    }

    @Test
    void missingPidSegmentRejected() {
        String raw = "MSH|^~\\&|HIS|HOSPITAL|RCV|HOSPITAL|20240101120000||ADT^A01|MSG011|P|2.5";
        IngestResult r = ingestAndLog("MSG011", raw);
        assertEquals(IngestStatus.REJECTED, r.status());
        assertEquals(RejectReason.MISSING_SEGMENT, r.rejectReason());
    }

    @Test
    void invalidEventTimeRejected() {
        String raw = msg("MSG012", "P1", "Zhang^San", "2024-01-01 10:00");
        IngestResult r = ingestAndLog("MSG012", raw);
        assertEquals(IngestStatus.REJECTED, r.status());
        assertEquals(RejectReason.INVALID_TIMESTAMP, r.rejectReason());
        assertEquals(0, store.count());
    }

    @Test
    void malformedStructureRejected() {
        IngestResult r = ingestAndLog("(无MSH)", "PID|1||P1^^^HIS||Zhang^San");
        assertEquals(IngestStatus.REJECTED, r.status());
        assertEquals(RejectReason.MISSING_SEGMENT, r.rejectReason());
    }
}
