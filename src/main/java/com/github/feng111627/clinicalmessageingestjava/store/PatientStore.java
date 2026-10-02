package com.github.feng111627.clinicalmessageingestjava.store;

import com.github.feng111627.clinicalmessageingestjava.merge.PatientRecord;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 患者记录的本地存储，仅内存。 */
@Repository
public class PatientStore {

    private final Map<String, PatientRecord> patients = new ConcurrentHashMap<>();

    public Optional<PatientRecord> find(String patientId) {
        return Optional.ofNullable(patients.get(patientId));
    }

    public PatientRecord findOrCreate(String patientId) {
        return patients.computeIfAbsent(patientId, PatientRecord::new);
    }

    /** 当前全部患者标识快照（用于冲突检测）。 */
    public Set<String> snapshotIds() {
        return Set.copyOf(patients.keySet());
    }

    public int size() {
        return patients.size();
    }
}
