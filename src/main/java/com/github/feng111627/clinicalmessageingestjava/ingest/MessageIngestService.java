package com.github.feng111627.clinicalmessageingestjava.ingest;

import com.github.feng111627.clinicalmessageingestjava.hl7.Hl7Message;
import com.github.feng111627.clinicalmessageingestjava.hl7.Hl7ParseException;
import com.github.feng111627.clinicalmessageingestjava.hl7.Hl7Parser;
import com.github.feng111627.clinicalmessageingestjava.hl7.Hl7Segment;
import com.github.feng111627.clinicalmessageingestjava.merge.FieldMismatch;
import com.github.feng111627.clinicalmessageingestjava.merge.MergeResult;
import com.github.feng111627.clinicalmessageingestjava.merge.PatientMergeService;
import com.github.feng111627.clinicalmessageingestjava.store.ConflictRecord;
import com.github.feng111627.clinicalmessageingestjava.store.ConflictStore;
import com.github.feng111627.clinicalmessageingestjava.store.MessageRecord;
import com.github.feng111627.clinicalmessageingestjava.store.MessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 消息接入编排：解析 -> 幂等（内容指纹）-> 关键字段校验 -> 按业务时间合并。
 * 全程只落本地状态，不依赖任何外部服务；所有依赖的存储均线程安全，支持多线程并发调用。
 */
@Service
public class MessageIngestService {

    private static final Logger log = LoggerFactory.getLogger(MessageIngestService.class);

    /** 批量接入容量上限的默认值，可用 ingest.max-batch-size 覆盖。 */
    public static final int DEFAULT_MAX_BATCH_SIZE = 500;

    private final Hl7Parser parser;
    private final MessageStore messageStore;
    private final PatientMergeService mergeService;
    private final ConflictStore conflictStore;
    private final int maxBatchSize;

    public MessageIngestService(Hl7Parser parser, MessageStore messageStore,
                                PatientMergeService mergeService, ConflictStore conflictStore,
                                @Value("${ingest.max-batch-size:" + DEFAULT_MAX_BATCH_SIZE + "}")
                                int maxBatchSize) {
        this.parser = parser;
        this.messageStore = messageStore;
        this.mergeService = mergeService;
        this.conflictStore = conflictStore;
        this.maxBatchSize = maxBatchSize;
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public IngestResult ingest(String rawMessage) {
        // 1. 解析与校验：失败按可区分原因拒收，不静默入库、不计入幂等存储
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
        String fingerprint = fingerprint(rawMessage);

        // 2. 幂等：区分“一字不差的重发”和“编号被复用但内容对不上”
        switch (messageStore.checkIdempotency(controlId, fingerprint)) {
            case DUPLICATE_IDENTICAL -> {
                log.info("ingest controlId={} status={} reason=消息编号与内容指纹均一致，判定为重复投递",
                        controlId, IngestStatus.DUPLICATE_RESENT);
                return IngestResult.of(IngestStatus.DUPLICATE_RESENT, controlId,
                        "消息编号 " + controlId + " 与内容指纹均与已处理消息一致，判定为重复投递，未产生重复记录");
            }
            case ID_REUSED -> {
                IngestResult result = IngestResult.of(IngestStatus.REJECTED_ID_REUSE, controlId,
                        "消息编号 " + controlId + " 已处理过但内容指纹对不上，判定为编号被复用（上游出错），"
                                + "已入册患者数据保持不动");
                messageStore.archive(record(result, patientId, fingerprint));
                log.info("ingest controlId={} status={} detail={}", controlId, result.status(), result.detail());
                return result;
            }
            case NEW -> {
                // 继续向下处理
            }
        }

        // 3. 关键患者字段校验：姓名为空的上游补传消息按可区分原因拒收，不写入患者档案
        IngestResult result;
        if (name.isBlank()) {
            result = IngestResult.of(IngestStatus.REJECTED_VALIDATION, controlId,
                    "关键患者字段 PID-5（姓名）为空，按数据校验拒收，未写入患者档案"
                            + "（与解析失败、更新冲突相区分）");
        } else {
            // 4. 按业务时间合并
            MergeResult merge = mergeService.merge(controlId, patientId, name, gender, businessTime);
            result = switch (merge.outcome()) {
                case APPLIED -> IngestResult.of(IngestStatus.ACCEPTED_NEW, controlId,
                        "业务时间 " + businessTime + " 不早于现有记录，已应用患者 " + patientId + " 的更新");
                case IGNORED_STALE -> IngestResult.of(IngestStatus.IGNORED_STALE, controlId,
                        "业务时间 " + businessTime + " 早于患者 " + patientId + " 现有记录，旧数据已忽略，未反盖新数据");
                case CONFLICT_PATIENT_ID -> IngestResult.of(IngestStatus.REJECTED_CONFLICT, controlId,
                        "患者标识冲突: " + patientId + " 与本地已有记录本地病案号相同但分配机构不同");
                case CONFLICT_UPDATE -> {
                    ConflictRecord trace = conflictStore.record(new ConflictRecord(
                            0, patientId, businessTime, controlId, merge.conflictingControlId(),
                            merge.mismatches(), conflictBasis(merge.mismatches())));
                    yield IngestResult.of(IngestStatus.CONFLICT_UPDATE, controlId,
                            "患者 " + patientId + " 在业务时间 " + businessTime
                                    + " 上已有来自消息 " + merge.conflictingControlId()
                                    + " 的记录，本次更新内容对不上，判定为更新冲突；已有数据保持不动，"
                                    + "已留痕（冲突序号 " + trace.sequence() + "）：" + trace.basis()
                                    + "。后续业务时间更靠后的正常更新仍可生效");
                }
            };
        }
        messageStore.archive(record(result, patientId, fingerprint));
        log.info("ingest controlId={} status={} detail={}", controlId, result.status(), result.detail());
        return result;
    }

    /**
     * 批量接入：批次内部允许乱序、重发与冲突消息，逐条按到达顺序处理，
     * 最终患者状态与逐条接入完全一致。
     * 容量上限确定：超过 maxBatchSize 或空批次时整批拒收，不处理任何一条。
     */
    public BatchIngestResult ingestBatch(List<String> rawMessages) {
        if (rawMessages == null || rawMessages.isEmpty()) {
            log.info("ingestBatch rejected reason=空批次 maxBatchSize={}", maxBatchSize);
            return BatchIngestResult.rejected("批次为空，整批拒收", maxBatchSize);
        }
        if (rawMessages.size() > maxBatchSize) {
            log.info("ingestBatch rejected reason=超过容量上限 size={} maxBatchSize={}",
                    rawMessages.size(), maxBatchSize);
            return BatchIngestResult.rejected(
                    "批次大小 " + rawMessages.size() + " 超过容量上限 " + maxBatchSize
                            + "，整批拒收，未处理任何一条", maxBatchSize);
        }
        List<IngestResult> results = new ArrayList<>(rawMessages.size());
        for (String raw : rawMessages) {
            results.add(ingest(raw));
        }
        return BatchIngestResult.of(results, maxBatchSize);
    }

    private MessageRecord record(IngestResult result, String patientId, String fingerprint) {
        return new MessageRecord(result.messageControlId(), patientId, fingerprint,
                result.status(), result.detail(), LocalDateTime.now());
    }

    /** 冲突判断依据：逐字段列出“本地值 vs 来报值”。 */
    private static String conflictBasis(List<FieldMismatch> mismatches) {
        StringBuilder sb = new StringBuilder("同一业务时间下字段内容不一致 -> ");
        for (int i = 0; i < mismatches.size(); i++) {
            FieldMismatch m = mismatches.get(i);
            if (i > 0) {
                sb.append("；");
            }
            sb.append(m.field()).append(": 本地[").append(m.existingValue())
                    .append("] vs 来报[").append(m.incomingValue()).append("]");
        }
        return sb.toString();
    }

    /** 内容指纹：消息原文（去首尾空白）的 SHA-256，用于区分一字不差的重发与编号复用。 */
    static String fingerprint(String rawMessage) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawMessage.strip().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
