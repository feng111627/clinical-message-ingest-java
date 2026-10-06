package com.github.feng111627.clinicalmessageingestjava.merge;

import java.util.List;

/**
 * 合并结论 + 可核对的判定依据。
 *
 * @param outcome            合并结论
 * @param differingFields    内容冲突时不一致的字段名列表（如 PID-5、PID-8）
 * @param existingControlId  内容冲突时，本地现有记录来源消息的控制标识
 */
public record MergeResult(MergeOutcome outcome, List<String> differingFields, String existingControlId) {

    public static MergeResult of(MergeOutcome outcome) {
        return new MergeResult(outcome, List.of(), null);
    }

    public static MergeResult conflict(List<String> differingFields, String existingControlId) {
        return new MergeResult(MergeOutcome.CONFLICT_SAME_TIME, List.copyOf(differingFields), existingControlId);
    }
}
