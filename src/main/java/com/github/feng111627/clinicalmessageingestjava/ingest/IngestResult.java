package com.github.feng111627.clinicalmessageingestjava.ingest;

import com.github.feng111627.clinicalmessageingestjava.hl7.ParseFailureReason;

/** 单条消息的处理结论：状态 + 可解释的判定依据。 */
public record IngestResult(
        IngestStatus status,
        String messageControlId,
        String detail,
        ParseFailureReason parseFailureReason) {

    public static IngestResult of(IngestStatus status, String controlId, String detail) {
        return new IngestResult(status, controlId, detail, null);
    }

    public static IngestResult rejected(String controlId, ParseFailureReason reason, String detail) {
        return new IngestResult(IngestStatus.REJECTED_PARSE, controlId, detail, reason);
    }
}
