package com.github.feng111627.clinicalmessageingestjava.ingest;

import java.time.Instant;

/**
 * 本地存储的患者记录（合并后的当前状态）。
 */
public final class PatientRecord {

    private final String patientId;
    private String patientName;
    private Instant lastEventTime;
    private String lastMessageControlId;

    public PatientRecord(String patientId, String patientName,
                         Instant lastEventTime, String lastMessageControlId) {
        this.patientId = patientId;
        this.patientName = patientName;
        this.lastEventTime = lastEventTime;
        this.lastMessageControlId = lastMessageControlId;
    }

    public String getPatientId() {
        return patientId;
    }

    public String getPatientName() {
        return patientName;
    }

    public Instant getLastEventTime() {
        return lastEventTime;
    }

    public String getLastMessageControlId() {
        return lastMessageControlId;
    }

    /**
     * 用一条更新的数据覆盖当前状态（调用方需保证 update 更新）。
     */
    public void apply(PatientUpdate update) {
        this.patientName = update.patientName();
        this.lastEventTime = update.eventTime();
        this.lastMessageControlId = update.messageControlId();
    }
}
