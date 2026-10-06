package com.github.feng111627.clinicalmessageingestjava.merge;

import java.util.List;

/** 一次合并的结论：结果 + 冲突痕迹（哪些字段对不上、与哪条消息冲突）。 */
public record MergeResult(
        MergeOutcome outcome,
        List<FieldMismatch> mismatches,
        String conflictingControlId) {

    public static MergeResult applied() {
        return new MergeResult(MergeOutcome.APPLIED, List.of(), null);
    }

    public static MergeResult stale() {
        return new MergeResult(MergeOutcome.IGNORED_STALE, List.of(), null);
    }

    public static MergeResult conflictUpdate(List<FieldMismatch> mismatches, String existingControlId) {
        return new MergeResult(MergeOutcome.CONFLICT_UPDATE, List.copyOf(mismatches), existingControlId);
    }

    public static MergeResult conflictPatientId() {
        return new MergeResult(MergeOutcome.CONFLICT_PATIENT_ID, List.of(), null);
    }
}
