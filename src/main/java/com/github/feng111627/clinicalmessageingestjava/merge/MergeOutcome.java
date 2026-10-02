package com.github.feng111627.clinicalmessageingestjava.merge;

/** 合并结论。 */
public enum MergeOutcome {
    APPLIED,            // 业务时间更新，已覆盖
    IGNORED_STALE,      // 旧数据，忽略（不得反盖新数据）
    CONFLICT_PATIENT_ID // 患者标识冲突
}
