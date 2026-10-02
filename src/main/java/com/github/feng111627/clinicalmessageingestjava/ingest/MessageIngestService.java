package com.github.feng111627.clinicalmessageingestjava.ingest;

import com.github.feng111627.clinicalmessageingestjava.hl7.Hl7Message;
import com.github.feng111627.clinicalmessageingestjava.hl7.Hl7ParseException;
import com.github.feng111627.clinicalmessageingestjava.hl7.Hl7Parser;
import com.github.feng111627.clinicalmessageingestjava.hl7.Hl7Segment;
import com.github.feng111627.clinicalmessageingestjava.merge.MergeOutcome;
import com.github.feng111627.clinicalmessageingestjava.merge.PatientMergeService;
import com.github.feng111627.clinicalmessageingestjava.store.MessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 消息接入编排：解析 -> 幂等 -> 按业务时间合并。
 * 全程只落本地状态，不依赖任何外部服务。
 */
@Service
public class MessageIngestService {

    private static final Logger log = LoggerFactory.getLogger(MessageIngestService.class);

    private final Hl7Parser parser;
    private final MessageStore messageStore;
    private final PatientMergeService mergeService;

    public MessageIngestService(Hl7Parser parser, MessageStore messageStore, PatientMergeService mergeService) {
        this.parser = parser;
        this.messageStore = messageStore;
        this.mergeService = mergeService;
    }

    public IngestResult ingest(String rawMessage) {
        // 1. 解析与校验：失败按可区分原因拒收，不静默入库
        Hl7Message message;
        try {
            message = parser.parse(rawMessage);
        } catch (Hl7ParseException e) {
            log.info("ingest controlId=<unknown> status={} reason={} detail={}",
                    IngestStatus.REJECTED_PARSE, e.getReason(), e.getMessage());
            return IngestResult.rejected(null, e.getReason(), e.getMessage());
        }

        Hl7Segment msh = message.segment("MSH");
        Hl7Segment pid = message.segment("PID");
        String controlId = msh.field(10);
        LocalDateTime businessTime = Hl7Parser.parseTimestamp(msh.field(7));
        String patientId = pid.field(3);
        String name = pid.field(5);
        String gender = pid.field(8);

        // 2. 幂等：同一控制标识重复投递，直接判定为重发
        if (!messageStore.recordIfAbsent(controlId)) {
            log.info("ingest controlId={} status={} reason=消息控制标识已处理过，判定为重复投递",
                    controlId, IngestStatus.DUPLICATE_RESENT);
            return IngestResult.of(IngestStatus.DUPLICATE_RESENT, controlId,
                    "消息控制标识 " + controlId + " 已处理过，判定为重复投递，未产生重复记录");
        }

        // 3. 按业务时间合并
        MergeOutcome outcome = mergeService.merge(patientId, name, gender, businessTime);
        IngestResult result = switch (outcome) {
            case APPLIED -> IngestResult.of(IngestStatus.ACCEPTED_NEW, controlId,
                    "业务时间 " + businessTime + " 不早于现有记录，已应用患者 " + patientId + " 的更新");
            case IGNORED_STALE -> IngestResult.of(IngestStatus.IGNORED_STALE, controlId,
                    "业务时间 " + businessTime + " 早于患者 " + patientId + " 现有记录，旧数据已忽略，未反盖新数据");
            case CONFLICT_PATIENT_ID -> IngestResult.of(IngestStatus.REJECTED_CONFLICT, controlId,
                    "患者标识冲突: " + patientId + " 与本地已有记录本地病案号相同但分配机构不同");
        };
        log.info("ingest controlId={} status={} detail={}", controlId, result.status(), result.detail());
        return result;
    }
}
