package com.github.feng111627.clinicalmessageingestjava.store;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 已见消息的本地存储：内容指纹（幂等依据）+ 处理档案（查询依据）。
 * 仅内存，不依赖外部服务；全部结构线程安全。
 */
@Repository
public class MessageStore {

    /** 幂等判定结果。 */
    public enum Idempotency {
        NEW,                // 编号未见过：新消息
        DUPLICATE_IDENTICAL,// 编号见过且内容指纹一致：一字不差的重发
        ID_REUSED           // 编号见过但内容指纹对不上：编号被复用（上游出错）
    }

    /** 消息编号 -> 首条内容指纹（幂等依据）。 */
    private final Map<String, String> fingerprintByControlId = new ConcurrentHashMap<>();
    /** 消息编号 -> 处理档案列表（同一编号可能有多条结论，如先拒收后复用）。 */
    private final Map<String, List<MessageRecord>> recordsByControlId = new ConcurrentHashMap<>();
    /** 患者标识 -> 处理档案列表。 */
    private final Map<String, List<MessageRecord>> recordsByPatientId = new ConcurrentHashMap<>();

    /**
     * 原子地完成幂等判定：若编号未见过则登记指纹。
     * 同一编号并发到达时，只有一个线程得到 NEW，其余得到重发/复用结论，
     * 同一条消息不会被重复计数。
     */
    public Idempotency checkIdempotency(String messageControlId, String fingerprint) {
        String existing = fingerprintByControlId.putIfAbsent(messageControlId, fingerprint);
        if (existing == null) {
            return Idempotency.NEW;
        }
        return existing.equals(fingerprint) ? Idempotency.DUPLICATE_IDENTICAL : Idempotency.ID_REUSED;
    }

    /** 归档一条消息的处理结论（每种结论只归档一次，重发不重复归档）。 */
    public void archive(MessageRecord record) {
        recordsByControlId.computeIfAbsent(record.messageControlId(), k -> new CopyOnWriteArrayList<>())
                .add(record);
        if (record.patientId() != null) {
            recordsByPatientId.computeIfAbsent(record.patientId(), k -> new CopyOnWriteArrayList<>())
                    .add(record);
        }
    }

    /** 按消息编号查询处理档案。 */
    public List<MessageRecord> findByControlId(String messageControlId) {
        return Optional.ofNullable(recordsByControlId.get(messageControlId))
                .map(List::copyOf)
                .orElse(List.of());
    }

    /** 按患者标识查询处理档案。 */
    public List<MessageRecord> findByPatient(String patientId) {
        return Optional.ofNullable(recordsByPatientId.get(patientId))
                .map(List::copyOf)
                .orElse(List.of());
    }

    public boolean hasSeen(String messageControlId) {
        return fingerprintByControlId.containsKey(messageControlId);
    }

    /** 已登记指纹的不同消息编号数（用于核对“同一条消息不被重复计数”）。 */
    public int size() {
        return fingerprintByControlId.size();
    }
}
