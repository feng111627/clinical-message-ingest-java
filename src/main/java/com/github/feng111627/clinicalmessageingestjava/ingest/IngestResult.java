package com.github.feng111627.clinicalmessageingestjava.ingest;

import com.github.feng111627.clinicalmessageingestjava.hl7.RejectReason;

/**
 * 一条消息的接入结果，包含判定状态与可解释的依据。
 *
 * @param status       判定状态
 * @param rejectReason 拒收原因（仅 status == REJECTED 时非空）
 * @param detail       人类可读的判定依据
 */
public record IngestResult(IngestStatus status, RejectReason rejectReason, String detail) {

    public static IngestResult of(IngestStatus status, String detail) {
        return new IngestResult(status, null, detail);
    }

    public static IngestResult rejected(RejectReason reason, String detail) {
        return new IngestResult(IngestStatus.REJECTED, reason, detail);
    }
}
