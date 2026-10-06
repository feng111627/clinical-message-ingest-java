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
     * 按业务时间合并：
     * - businessTime 早于现有记录 -> STALE，忽略，不反盖新数据；
     * - businessTime 相同但内容对不上 -> CONFLICT，已有数据保持不动，返回差异字段；
     * - 其余（更新、或同时刻内容一致）-> APPLIED，覆盖。
     */
    public synchronized ApplyResult apply(String name, String gender, LocalDateTime businessTime, String controlId) {
        if (lastUpdated != null) {
            if (businessTime.isBefore(lastUpdated)) {
                return new ApplyResult(ApplyOutcome.STALE, List.of());
            }
            if (businessTime.isEqual(lastUpdated)) {
                List<String> differing = differingFields(name, gender);
                if (!differing.isEmpty()) {
                    // 同一时间、内容对不上：判冲突，已有数据保持不动
                    return new ApplyResult(ApplyOutcome.CONFLICT, differing);
                }
            }
        }
        this.name = name;
        this.gender = gender;
        this.lastUpdated = businessTime;
        this.lastControlId = controlId;
        return new ApplyResult(ApplyOutcome.APPLIED, List.of());
    }

    /** 列出与现有内容对不上的字段（PID-5 姓名 / PID-8 性别）。 */
    private List<String> differingFields(String name, String gender) {
        List<String> differing = new ArrayList<>(2);
        if (!java.util.Objects.equals(this.name, name)) {
            differing.add("PID-5");
        }
        if (!java.util.Objects.equals(this.gender, gender)) {
            differing.add("PID-8");
        }
        return differing;
    }
}
