package com.github.feng111627.clinicalmessageingestjava.merge;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地患者记录：只保留业务时间（MSH-7）最新的数据。
 * 旧数据不得反盖新数据；同一业务时间内容对不上判为冲突，已有数据保持不动。
 */
public class PatientRecord {

    /** 单次合并的结论。 */
    public enum ApplyOutcome { APPLIED, STALE, CONFLICT }

    /** 冲突时用于留痕的差异说明。 */
    public record ApplyResult(ApplyOutcome outcome, List<String> differingFields) {}

    private final String patientId;
    private String name;
    private String gender;
    private LocalDateTime lastUpdated;   // 最近一次应用的业务时间
    private String lastControlId;        // 最近一次应用来源消息的控制标识

    public PatientRecord(String patientId) {
        this.patientId = patientId;
    }

    public String getPatientId() { return patientId; }
    public String getName() { return name; }
    public String getGender() { return gender; }
    public LocalDateTime getLastUpdated() { return lastUpdated; }
    public String getLastControlId() { return lastControlId; }

    /**
     * 按业务时间合并（骨架占位实现，后续填充完整规则）。
     */
    public synchronized ApplyResult apply(String name, String gender, LocalDateTime businessTime, String controlId) {
        // TODO: 填充  stale / 同时间冲突 / 应用 的完整规则
        if (lastUpdated != null && businessTime.isBefore(lastUpdated)) {
            return new ApplyResult(ApplyOutcome.STALE, List.of());
        }
        this.name = name;
        this.gender = gender;
        this.lastUpdated = businessTime;
        this.lastControlId = controlId;
        return new ApplyResult(ApplyOutcome.APPLIED, List.of());
    }
}
