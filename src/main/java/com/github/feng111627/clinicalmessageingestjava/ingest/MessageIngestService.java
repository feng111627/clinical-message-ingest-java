package com.github.feng111627.clinicalmessageingestjava.ingest;

import com.github.feng111627.clinicalmessageingestjava.hl7.Hl7Message;
import com.github.feng111627.clinicalmessageingestjava.hl7.Hl7ParseException;
import com.github.feng111627.clinicalmessageingestjava.hl7.Hl7Parser;
import com.github.feng111627.clinicalmessageingestjava.hl7.Hl7Segment;
import com.github.feng111627.clinicalmessageingestjava.merge.MergeOutcome;
import com.github.feng111627.clinicalmessageingestjava.merge.MergeResult;
import com.github.feng111627.clinicalmessageingestjava.merge.PatientMergeService;
import com.github.feng111627.clinicalmessageingestjava.store.ConflictRecord;
import com.github.feng111627.clinicalmessageingestjava.store.ConflictStore;
import com.github.feng111627.clinicalmessageingestjava.store.MessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 消息接入编排：解析 -> 关键字段校验 -> 幂等（区分重发/编号复用）-> 按业务时间合并（含同时间冲突留痕）。
 * 全程只落本地状态，不依赖任何外部服务。
 */
@Service
public class MessageIngestService {

    private static final Logger log = LoggerFactory.getLogger(MessageIngestService.class);

    private final Hl7Parser parser;
    private final MessageStore messageStore;
    private final PatientMergeService mergeService;
    private final ConflictStore conflictStore;
    private final int maxBatchSize;

    public MessageIngestService(Hl7Parser parser, MessageStore messageStore,
                                PatientMergeService mergeService, ConflictStore conflictStore,
                                @Value("${ingest.max-batch-size:100}") int maxBatchSize) {
        this.parser = parser;
        this.messageStore = messageStore;
        this.mergeService = mergeService;
        this.conflictStore = conflictStore;
        this.maxBatchSize = maxBatchSize;
    }

    public int maxBatchSize() {
        return maxBatchSize;
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

        // 2. 幂等：区分"内容一致的重发"与"编号复用"（原子判定，并发下同编号只放行一条）
        String contentHash = sha256(rawMessage.strip());
        MessageStore.MarkResult mark = messageStore.markIfAbsent(controlId, contentHash);
        if (mark == MessageStore.MarkResult.DUPLICATE) {
            String original = messageStore.findByControlId(controlId)
                    .map(r -> String.valueOf(r.status()))
                    .orElse("处理中");
            log.info("ingest controlId={} status={} reason=消息控制标识与内容均一致，判定为重复投递（原结论={}）",
                    controlId, IngestStatus.DUPLICATE_RESENT, original);
            return IngestResult.of(IngestStatus.DUPLICATE_RESENT, controlId,
                    "消息控制标识 " + controlId + " 已处理过且内容完全一致，判定为重复投递，未产生重复记录（原结论: "
                            + original + "）");
        }
        if (mark == MessageStore.MarkResult.ID_REUSED) {
            log.info("ingest controlId={} status={} reason=消息控制标识被复用但内容不一致，判定为编号复用，不动已有数据",
                    controlId, IngestStatus.REJECTED_ID_REUSED);
            return IngestResult.of(IngestStatus.REJECTED_ID_REUSED, controlId,
                    "消息控制标识 " + controlId + " 已被内容不同的消息使用，判定为编号复用（上游出错），"
                            + "已拒收且未改动患者数据");
        }

        // 3. 关键患者字段校验：姓名为空按独立原因拒收，不写入患者档案，避免污染下游对账
        if (name.isBlank()) {
            IngestResult result = IngestResult.of(IngestStatus.REJECTED_MISSING_KEY_FIELD, controlId,
                    "关键患者字段 PID-5（姓名）为空，按 REJECTED_MISSING_KEY_FIELD 拒收，"
                            + "未写入患者档案（区别于解析失败与更新冲突）");
            messageStore.recordOutcome(controlId, result.status(), result.detail(), patientId);
            log.info("ingest controlId={} status={} detail={}", controlId, result.status(), result.detail());
            return result;
        }

        // 4. 按业务时间合并
        MergeResult merged = mergeService.merge(patientId, name, gender, businessTime, controlId);
        IngestResult result = toResult(merged, controlId, patientId, businessTime);
        messageStore.recordOutcome(controlId, result.status(), result.detail(), patientId);
        log.info("ingest controlId={} status={} detail={}", controlId, result.status(), result.detail());
        return result;
    }

    /** 批量接入：整批超限则确定性拒收（一条都不处理）；否则按到达顺序逐条处理。 */
    public BatchIngestResult ingestBatch(List<String> rawMessages) {
        if (rawMessages.size() > maxBatchSize) {
            log.info("ingestBatch rejected size={} maxBatchSize={} reason=超过批量容量上限，整批拒收",
                    rawMessages.size(), maxBatchSize);
            return BatchIngestResult.rejected(rawMessages.size(), maxBatchSize);
        }
        List<IngestResult> results = new ArrayList<>(rawMessages.size());
        for (String raw : rawMessages) {
            results.add(ingest(raw));
        }
        return BatchIngestResult.of(results, maxBatchSize);
    }

    private IngestResult toResult(MergeResult merged, String controlId, String patientId,
                                  LocalDateTime businessTime) {
        MergeOutcome outcome = merged.outcome();
        return switch (outcome) {
            case APPLIED -> IngestResult.of(IngestStatus.ACCEPTED_NEW, controlId,
                    "业务时间 " + businessTime + " 不早于现有记录，已应用患者 " + patientId + " 的更新");
            case IGNORED_STALE -> IngestResult.of(IngestStatus.IGNORED_STALE, controlId,
                    "业务时间 " + businessTime + " 早于患者 " + patientId + " 现有记录，旧数据已忽略，未反盖新数据");
            case CONFLICT_SAME_TIME -> {
                String detail = "业务时间 " + businessTime + " 与患者 " + patientId + " 现有记录（来源消息 "
                        + merged.existingControlId() + "）相同但内容对不上，差异字段 " + merged.differingFields()
                        + "，判定为更新冲突，已有数据保持不动，待人工核对";
                // 冲突留痕：哪条跟哪条冲突、哪些字段对不上、判断依据，事后可查
                conflictStore.record(new ConflictRecord(patientId, businessTime,
                        merged.existingControlId(), controlId, merged.differingFields(), detail, Instant.now()));
                yield IngestResult.of(IngestStatus.CONFLICT_UPDATE, controlId, detail);
            }
            case CONFLICT_PATIENT_ID -> IngestResult.of(IngestStatus.REJECTED_CONFLICT, controlId,
                    "患者标识冲突: " + patientId + " 与本地已有记录本地病案号相同但分配机构不同");
        };
    }

    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
