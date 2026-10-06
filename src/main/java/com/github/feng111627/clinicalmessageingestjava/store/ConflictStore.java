package com.github.feng111627.clinicalmessageingestjava.store;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 更新冲突留痕的本地存储，仅内存，不依赖外部服务。 */
@Repository
public class ConflictStore {

    private final List<ConflictRecord> conflicts = new CopyOnWriteArrayList<>();

    public void record(ConflictRecord conflict) {
        conflicts.add(conflict);
    }

    public List<ConflictRecord> findByPatientId(String patientId) {
        return conflicts.stream().filter(c -> c.patientId().equals(patientId)).toList();
    }

    public List<ConflictRecord> findAll() {
        return List.copyOf(conflicts);
    }

    public int size() {
        return conflicts.size();
    }
}
