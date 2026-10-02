package com.github.feng111627.clinicalmessageingestjava.ingest;

/**
 * 消息接入判定结果。
 */
public enum IngestStatus {
    /** 新消息，新建患者记录 */
    ACCEPTED_NEW,
    /** 新消息，业务时间更新，合并覆盖已有患者记录 */
    ACCEPTED_MERGED,
    /** 新消息，但业务时间不晚于已有数据，不覆盖（旧数据不得盖掉新数据） */
    ACCEPTED_STALE,
    /** 同一消息控制标识且内容一致，属重复投递，幂等忽略 */
    DUPLICATE,
    /** 患者标识冲突：同一消息控制标识被内容不同的消息复用 */
    CONFLICT_REPLAY,
    /** 更新冲突：同一患者同一业务时间出现内容不同的更新，无法确定先后 */
    CONFLICT_UPDATE,
    /** 解析或校验失败被拒收，具体原因见 RejectReason */
    REJECTED
}
