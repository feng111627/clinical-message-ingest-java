package com.github.feng111627.clinicalmessageingestjava.ingest;

/** 消息接入的判定结果。 */
public enum IngestStatus {
    ACCEPTED_NEW,      // 新消息，已按业务时间应用
    DUPLICATE_RESENT,  // 重复投递（控制标识已见），未产生重复记录或重复更新
    IGNORED_STALE,     // 乱序到达的更旧数据，已忽略，未反盖较新数据
    REJECTED_PARSE,    // 解析/校验失败（原因见 parseFailureReason）
    REJECTED_CONFLICT  // 患者标识冲突
}
