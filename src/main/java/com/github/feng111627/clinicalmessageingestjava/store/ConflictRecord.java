package com.github.feng111627.clinicalmessageingestjava.store;

import com.github.feng111627.clinicalmessageingestjava.merge.FieldMismatch;

import java.time.LocalDateTime;
import java.util.List;

/** 一次“同一时间、内容对不上”的更新冲突痕迹，供事后人工核对。 */
public record ConflictRecord(
        long sequence,
        String patientId,
        LocalDateTime businessTime,
        String incomingControlId,
        String existingControlId,
        List<FieldMismatch> mismatches,
        String basis) {
}
