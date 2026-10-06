package com.github.feng111627.clinicalmessageingestjava.ingest;

/** 消息接入的判定结果。 */
public enum IngestStatus {
    ACCEPTED_NEW,               // 新消息，已按业务时间应用
    DUPLICATE_RESENT,           // 编号相同且内容完全一致的重发，未产生重复记录或重复更新
    IGNORED_STALE,              // 乱序到达的更旧数据，已忽略，未反盖较新数据
    REJECTED_PARSE,             // 解析/校验失败（原因见 parseFailureReason）
    REJECTED_MISSING_KEY_FIELD, // 关键患者字段（如姓名 PID-5）为空，按独立原因拒收
    REJECTED_ID_REUSED,         // 消息编号被复用但内容对不上（上游出错），不动已有数据
    REJECTED_CONFLICT,          // 患者标识冲突（本地病案号相同、分配机构不同）
    CONFLICT_UPDATE             // 同一业务时间、内容对不上的更新冲突，已有数据保持不动
}
