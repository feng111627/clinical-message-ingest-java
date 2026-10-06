package com.github.feng111627.clinicalmessageingestjava.merge;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 本地患者记录：只保留业务时间（MSH-7）最新的数据。
 * 旧数据不得反盖新数据；同一业务时间内容对不上的更新判为冲突，已有数据保持不动。
 * 所有状态迁移都在本对象的监视器锁内完成，并发更新安全。
 */
public class PatientRecord {
    private final String patientId;
    private String name;
    private String gender;
    private LocalDateTime lastUpdated;        // 最近一次应用的业务时间
    private String lastAppliedControlId;      // 最近一次应用的消息编号（冲突溯源用）

    public PatientRecord(String patientId) {
        this.patientId = patientId;
    }

    public String getPatientId() { return patientId; }

    public synchronized String getName() { return name; }
    public synchronized String getGender() { return gender; }
    public synchronized LocalDateTime getLastUpdated() { return lastUpdated; }
    public synchronized String getLastAppliedControlId() { return lastAppliedControlId; }

    /**
     * 按业务时间合并一次更新。
     * - businessTime 更旧 -> IGNORED_STALE，不动现有数据；
     * - businessTime 相同且内容对不上 -> CONFLICT_UPDATE，不动现有数据，返回逐字段差异；
     * - 其余（更新、或同时间内容一致）-> APPLIED。
     */
    public synchronized MergeResult apply(String controlId, String name, String gender,
                                          LocalDateTime businessTime) {
        if (lastUpdated != null) {
            if (businessTime.isBefore(lastUpdated)) {
                return MergeResult.stale();
            }
            if (businessTime.equals(lastUpdated)) {
                List<FieldMismatch> mismatches = diff(name, gender);
                if (!mismatches.isEmpty()) {
                    return MergeResult.conflictUpdate(mismatches, lastAppliedControlId);
                }
                // 同时间、内容一致：幂等应用，结论仍是已应用
            }
        }
        this.name = name;
        this.gender = gender;
        this.lastUpdated = businessTime;
        this.lastAppliedControlId = controlId;
        return MergeResult.applied();
    }

    /** 逐字段比对，列出对不上的字段（判断依据，供人工核对）。 */
    private List<FieldMismatch> diff(String incomingName, String incomingGender) {
        List<FieldMismatch> mismatches = new ArrayList<>();
        if (!Objects.equals(this.name, incomingName)) {
            mismatches.add(new FieldMismatch("PID-5 姓名", this.name, incomingName));
        }
        if (!Objects.equals(this.gender, incomingGender)) {
            mismatches.add(new FieldMismatch("PID-8 性别", this.gender, incomingGender));
        }
        return mismatches;
    }
}
