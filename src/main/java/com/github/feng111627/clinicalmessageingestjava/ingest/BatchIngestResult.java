package com.github.feng111627.clinicalmessageingestjava.ingest;

import java.util.List;

/**
 * 批量接入结论：要么整批按到达顺序逐条处理，要么整批确定性拒收（不处理任何一条）。
 *
 * @param accepted         批次是否被受理
 * @param rejectionReason  整批拒收原因（受理时为 null）
 * @param maxBatchSize     当前生效的批量容量上限
 * @param results          每条消息的处理结论，顺序与到达顺序一致
 */
public record BatchIngestResult(
        boolean accepted,
        String rejectionReason,
        int maxBatchSize,
        List<IngestResult> results) {

    public static BatchIngestResult rejected(int actualSize, int maxBatchSize) {
        return new BatchIngestResult(false,
                "批次大小 " + actualSize + " 超过上限 " + maxBatchSize + "，整批拒收，未处理任何消息",
                maxBatchSize, List.of());
    }

    public static BatchIngestResult of(List<IngestResult> results, int maxBatchSize) {
        return new BatchIngestResult(true, null, maxBatchSize, List.copyOf(results));
    }
}
