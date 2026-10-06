package com.github.feng111627.clinicalmessageingestjava.store;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 一次"同一业务时间、内容对不上"的更新冲突留痕，供人工核对。
 *
 * @param patientId          患者标识
 * @param businessTime       冲突发生的业务时间
 * @param existingControlId  本地现有记录来源消息的控制标识
 * @param incomingControlId  冲突的到达消息控制标识
 * @param differingFields    对不上的字段名列表
 * @param detail             判定依据
 * @param recordedAt         本地留痕时间
 */
public record ConflictRecord(
        String patientId,
        LocalDateTime businessTime,
        String existingControlId,
        String incomingControlId,
        List<String> differingFields,
        String detail,
        Instant recordedAt) {
}
