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
     * 幂等判定（原子）：同一控制标识
     * - 首次到达 -> NEW，并以 PENDING 占位（并发下同编号消息只放行一条进入后续处理）；
     * - 已存在且内容指纹一致 -> DUPLICATE（一字不差的重发）；
     * - 已存在但内容指纹不一致 -> ID_REUSED（编号被复用，上游出错）。
     */
    public MarkResult markIfAbsent(String controlId, String contentHash) {
        MarkResult[] verdict = new MarkResult[1];
        messages.compute(controlId, (id, existing) -> {
            if (existing == null) {
                verdict[0] = MarkResult.NEW;
                return new MessageRecord(id, contentHash, null, null, null, Instant.now());
            }
            verdict[0] = existing.contentHash().equals(contentHash) ? MarkResult.DUPLICATE : MarkResult.ID_REUSED;
            return existing;
        });
        return verdict[0];
    }

    /** 回填某条消息的最终处理结论（保留首次登记的内容指纹）。 */
    public void recordOutcome(String controlId, IngestStatus status, String detail, String patientId) {
        messages.computeIfPresent(controlId, (id, existing) -> new MessageRecord(
                id, existing.contentHash(), status, detail, patientId, Instant.now()));
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
