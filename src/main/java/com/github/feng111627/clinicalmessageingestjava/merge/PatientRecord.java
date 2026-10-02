package com.github.feng111627.clinicalmessageingestjava.merge;

import java.time.LocalDateTime;

/**
 * 本地患者记录：只保留业务时间（MSH-7）最新的数据。
 * 旧数据不得反盖新数据。
 */
public class PatientRecord {
    private final String patientId;
    private String name;
    private String gender;
    private LocalDateTime lastUpdated; // 最近一次应用的业务时间

    public PatientRecord(String patientId) {
        this.patientId = patientId;
    }

    public String getPatientId() { return patientId; }
    public String getName() { return name; }
    public String getGender() { return gender; }
    public LocalDateTime getLastUpdated() { return lastUpdated; }

    /**
     * 仅当 businessTime 不早于当前记录的业务时间时才覆盖。
     *
     * @return true 表示已应用；false 表示是更旧的乱序数据，已忽略
     */
    public synchronized boolean apply(String name, String gender, LocalDateTime businessTime) {
        if (lastUpdated != null && businessTime.isBefore(lastUpdated)) {
            return false;
        }
        this.name = name;
        this.gender = gender;
        this.lastUpdated = businessTime;
        return true;
    }
}
