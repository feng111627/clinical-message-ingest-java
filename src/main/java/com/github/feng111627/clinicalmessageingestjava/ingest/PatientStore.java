package com.github.feng111627.clinicalmessageingestjava.ingest;

import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 患者记录的本地内存存储（状态只落在本地，不依赖任何外部服务）。
 */
@Repository
public class PatientStore {

    private final Map<String, PatientRecord> records = new ConcurrentHashMap<>();

    public Optional<PatientRecord> findByPatientId(String patientId) {
        return Optional.ofNullable(records.get(patientId));
    }

    public void save(PatientRecord record) {
        records.put(record.getPatientId(), record);
    }

    public int count() {
        return records.size();
    }
}
