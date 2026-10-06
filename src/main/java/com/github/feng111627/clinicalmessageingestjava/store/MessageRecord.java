package com.github.feng111627.clinicalmessageingestjava.store;

import com.github.feng111627.clinicalmessageingestjava.ingest.IngestStatus;

import java.time.LocalDateTime;

/** 一条已解析消息的处理档案：结论 + 内容指纹，供按患者/按消息编号查询。 */
public record MessageRecord(
        String messageControlId,
        String patientId,
        String fingerprint,
        IngestStatus status,
        String detail,
        LocalDateTime processedAt) {
}
