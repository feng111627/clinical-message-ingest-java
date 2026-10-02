package com.github.feng111627.clinicalmessageingestjava.ingest;

import com.github.feng111627.clinicalmessageingestjava.hl7.Hl7Message;
import com.github.feng111627.clinicalmessageingestjava.hl7.Hl7ParseException;
import com.github.feng111627.clinicalmessageingestjava.hl7.Hl7Parser;
import com.github.feng111627.clinicalmessageingestjava.hl7.Hl7Timestamp;
import com.github.feng111627.clinicalmessageingestjava.hl7.RejectReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 消息接入服务：解析 → 必填校验 → 幂等判定 → 按业务时间合并。
 *
 * <p>合并规则：</p>
 * <ul>
 *   <li>业务时间（EVN-2，缺省 MSH-7）更新的消息覆盖旧数据；</li>
 *   <li>业务时间更旧的消息不改状态（旧数据不得盖掉新数据）；</li>
 *   <li>业务时间相同但内容不同的更新判定为冲突，不应用。</li>
 * </ul>
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final Hl7Parser parser;
    private final PatientStore store;
    private final MessageJournal journal;

    public IngestService(Hl7Parser parser, PatientStore store, MessageJournal journal) {
        this.parser = parser;
        this.store = store;
        this.journal = journal;
    }

    /**
     * 接入一条原始 HL7 消息。任何情况下都返回明确的判定结果，不静默丢弃。
     */
    public IngestResult ingest(String rawMessage) {
        Hl7Message message;
        try {
            message = parser.parse(rawMessage);
        } catch (Hl7ParseException e) {
            log.info("ingest result=REJECTED reason={} detail={}", e.getReason(), e.getMessage());
            return IngestResult.rejected(e.getReason(), e.getMessage());
        }

        PatientUpdate update;
        try {
            update = extractUpdate(message);
        } catch (Hl7ParseException e) {
            log.info("ingest result=REJECTED reason={} detail={}", e.getReason(), e.getMessage());
            return IngestResult.rejected(e.getReason(), e.getMessage());
        }

        String controlId = update.messageControlId();

        // ---- 幂等判定 ----
        var seen = journal.find(controlId);
        if (seen.isPresent()) {
            if (seen.get().matches(update)) {
                log.info("ingest result=DUPLICATE controlId={} 依据=同一消息控制标识且内容指纹一致，幂等忽略", controlId);
                return IngestResult.of(IngestStatus.DUPLICATE,
                        "消息 " + controlId + " 已处理过且内容一致，重复投递被忽略");
            }
            log.info("ingest result=CONFLICT_REPLAY controlId={} 依据=消息控制标识被内容不同的消息复用", controlId);
            return IngestResult.of(IngestStatus.CONFLICT_REPLAY,
                    "消息控制标识 " + controlId + " 已被内容不同的消息占用，判定为患者标识冲突");
        }

        // ---- 按业务时间合并 ----
        IngestResult result = merge(update);
        // 只有得出明确结论后才登记日志，保证重发判定基于已受理的消息
        journal.recordIfAbsent(update);
        log.info("ingest result={} controlId={} patientId={} eventTime={} 依据={}",
                result.status(), controlId, update.patientId(), update.eventTime(), result.detail());
        return result;
    }

    private IngestResult merge(PatientUpdate update) {
        var existing = store.findByPatientId(update.patientId());
        if (existing.isEmpty()) {
            store.save(new PatientRecord(update.patientId(), update.patientName(),
                    update.eventTime(), update.messageControlId()));
            return IngestResult.of(IngestStatus.ACCEPTED_NEW,
                    "患者 " + update.patientId() + " 首次出现，新建记录");
        }
        PatientRecord record = existing.get();
        int cmp = update.eventTime().compareTo(record.getLastEventTime());
        if (cmp > 0) {
            record.apply(update);
            store.save(record);
            return IngestResult.of(IngestStatus.ACCEPTED_MERGED,
                    "业务时间 " + update.eventTime() + " 晚于当前 " + record.getLastEventTime() + "，覆盖旧数据");
        }
        if (cmp == 0 && !record.getPatientName().equals(update.patientName())) {
            return IngestResult.of(IngestStatus.CONFLICT_UPDATE,
                    "业务时间与当前记录相同（" + update.eventTime() + "）但内容不同，无法确定先后，不应用");
        }
        return IngestResult.of(IngestStatus.ACCEPTED_STALE,
                "业务时间 " + update.eventTime() + " 不晚于当前 " + record.getLastEventTime()
                        + "，保留较新数据，本次不覆盖");
    }

    /**
     * 从解析后的消息中提取患者更新，并做必填字段与时间格式校验。
     */
    private PatientUpdate extractUpdate(Hl7Message message) throws Hl7ParseException {
        Hl7Message.Segment msh = message.firstSegment("MSH")
                .orElseThrow(() -> new Hl7ParseException(RejectReason.MISSING_SEGMENT, "缺少 MSH 段"));

        String controlId = msh.field(10).orElseThrow(() ->
                new Hl7ParseException(RejectReason.MISSING_FIELD, "MSH-10 消息控制标识缺失"));
        String mshTime = msh.field(7).orElseThrow(() ->
                new Hl7ParseException(RejectReason.MISSING_FIELD, "MSH-7 消息时间缺失"));
        Instant messageTime = Hl7Timestamp.parse("MSH-7", mshTime);

        Hl7Message.Segment pid = message.firstSegment("PID")
                .orElseThrow(() -> new Hl7ParseException(RejectReason.MISSING_SEGMENT, "缺少 PID 段"));
        String patientId = pid.field(3)
                .map(v -> v.split("\\^", -1)[0])
                .filter(v -> !v.isEmpty())
                .orElseThrow(() ->
                        new Hl7ParseException(RejectReason.MISSING_FIELD, "PID-3 患者标识缺失"));
        String patientName = pid.field(5).orElseThrow(() ->
                new Hl7ParseException(RejectReason.MISSING_FIELD, "PID-5 患者姓名缺失"));

        // 业务时间：优先 EVN-2（事件记录时间），缺省回退 MSH-7
        Instant eventTime = messageTime;
        var evn = message.firstSegment("EVN");
        if (evn.isPresent() && evn.get().field(2).isPresent()) {
            eventTime = Hl7Timestamp.parse("EVN-2", evn.get().field(2).get());
        }

        return new PatientUpdate(controlId, patientId, patientName, eventTime);
    }
}
