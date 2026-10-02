package com.github.feng111627.clinicalmessageingestjava.hl7;

/**
 * 消息拒收原因，按可区分的类别划分。
 */
public enum RejectReason {
    /** 必填字段缺失（如 MSH-10 消息控制标识、PID-3 患者标识） */
    MISSING_FIELD,
    /** 段结构错误（段名非法、字段数量不足、空消息等） */
    MALFORMED_SEGMENT,
    /** 时间格式非法（如 MSH-7 / EVN-2 无法按 HL7 TS 解析） */
    INVALID_TIMESTAMP,
    /** 必需段缺失（如缺少 MSH 或 PID） */
    MISSING_SEGMENT
}
