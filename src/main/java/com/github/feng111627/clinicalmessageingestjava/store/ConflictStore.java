package com.github.feng111627.clinicalmessageingestjava.store;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** 更新冲突痕迹的本地存储，仅内存，不依赖外部服务。 */
@Repository
public class ConflictStore {

    private final AtomicLong sequence = new AtomicLong();
    private final List<ConflictRecord> records = new CopyOnWriteArrayList<>();

    /** 留痕并返回带序号的记录。 */
    public ConflictRecord record(ConflictRecord withoutSequence) {
        ConflictRecord saved = new ConflictRecord(
                sequence.incrementAndGet(),
                withoutSequence.patientId(),
                withoutSequence.businessTime(),
                withoutSequence.incomingControlId(),
                withoutSequence.existingControlId(),
                withoutSequence.mismatches(),
                withoutSequence.basis());
        records.add(saved);
        return saved;
    }

    /** 按患者查询冲突痕迹。 */
    public List<ConflictRecord> findByPatient(String patientId) {
        return records.stream().filter(r -> r.patientId().equals(patientId)).toList();
    }

    /** 按消息编号查询冲突痕迹（无论它是冲突的发起方还是被冲突方）。 */
    public List<ConflictRecord> findByControlId(String controlId) {
        return records.stream()
                .filter(r -> r.incomingControlId().equals(controlId)
                        || controlId.equals(r.existingControlId()))
                .toList();
    }

    public List<ConflictRecord> all() {
        return List.copyOf(records);
    }

    public int size() {
        return records.size();
    }
}
