package com.github.feng111627.clinicalmessageingestjava.store;

import com.github.feng111627.clinicalmessageingestjava.ingest.IngestStatus;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已处理消息的本地存储：以消息控制标识为键，保存内容指纹与处理结论。
 * 既是幂等依据，也是事后查询依据；仅内存，不依赖外部服务。
 */
@Repository
public class MessageStore {

    /** 幂等判定结果。 */
    public enum MarkResult { NEW, DUPLICATE, ID_REUSED }

    private final Map<String, MessageRecord> messages = new ConcurrentHashMap<>();

    /**
     * 骨架占位实现：按控制标识 + 内容指纹判定 NEW / DUPLICATE / ID_REUSED。
     */
    public MarkResult markIfAbsent(String controlId, String contentHash) {
        // TODO: 填充原子判定逻辑
        MessageRecord existing = messages.get(controlId);
        if (existing == null) {
            messages.put(controlId, new MessageRecord(controlId, contentHash, null, null, null, Instant.now()));
            return MarkResult.NEW;
        }
        return existing.contentHash().equals(contentHash) ? MarkResult.DUPLICATE : MarkResult.ID_REUSED;
    }

    /** 回填某条消息的最终处理结论（骨架占位实现）。 */
    public void recordOutcome(String controlId, IngestStatus status, String detail, String patientId) {
        // TODO: 填充结论回填逻辑
        MessageRecord existing = messages.get(controlId);
        if (existing != null) {
            messages.put(controlId, new MessageRecord(controlId, existing.contentHash(),
                    status, detail, patientId, Instant.now()));
        }
    }

    public Optional<MessageRecord> findByControlId(String controlId) {
        return Optional.ofNullable(messages.get(controlId));
    }

    public List<MessageRecord> findByPatientId(String patientId) {
        return messages.values().stream()
                .filter(m -> patientId.equals(m.patientId()))
                .toList();
    }

    public boolean hasSeen(String messageControlId) {
        return messages.containsKey(messageControlId);
    }

    public int size() {
        return messages.size();
    }
}
