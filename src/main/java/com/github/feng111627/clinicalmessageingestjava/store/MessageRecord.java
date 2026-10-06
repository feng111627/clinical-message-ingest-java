package com.github.feng111627.clinicalmessageingestjava.store;

import com.github.feng111627.clinicalmessageingestjava.ingest.IngestStatus;

import java.time.Instant;

/**
 * 一条已处理消息的本地档案：内容指纹 + 处理结论，用于幂等判定与事后查询。
 *
 * @param controlId   消息控制标识（MSH-10）
 * @param contentHash 消息内容指纹（用于区分"一字不差的重发"与"编号复用"）
 * @param status      处理结论
 * @param detail      判定依据
 * @param patientId   涉及的患者标识（PID-3），解析失败时可能为 null
 * @param processedAt 本地处理时间
 */
public record MessageRecord(
        String controlId,
        String contentHash,
        IngestStatus status,
        String detail,
        String patientId,
        Instant processedAt) {
}
