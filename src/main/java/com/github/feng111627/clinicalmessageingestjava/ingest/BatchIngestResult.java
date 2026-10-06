package com.github.feng111627.clinicalmessageingestjava.ingest;

import java.util.List;

/**
 * 一批消息的处理结论。
 * accepted=false 表示整批被拒收（如超过容量上限），此时 results 为空、不处理任何一条。
 */
public record BatchIngestResult(
        boolean accepted,
        String detail,
        int maxBatchSize,
        List<IngestResult> results) {

    public static BatchIngestResult rejected(String detail, int maxBatchSize) {
        return new BatchIngestResult(false, detail, maxBatchSize, List.of());
    }

    public static BatchIngestResult of(List<IngestResult> results, int maxBatchSize) {
        return new BatchIngestResult(true,
                "已按到达顺序逐条处理 " + results.size() + " 条消息", maxBatchSize, List.copyOf(results));
    }
}
