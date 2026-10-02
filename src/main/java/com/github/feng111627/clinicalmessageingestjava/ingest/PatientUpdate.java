package com.github.feng111627.clinicalmessageingestjava.ingest;

import java.time.Instant;

/**
 * 从一条 HL7 消息中提取的患者更新业务数据。
 *
 * @param messageControlId MSH-10 消息控制标识（幂等键）
 * @param patientId        PID-3 患者标识
 * @param patientName      PID-5 患者姓名
 * @param eventTime        业务时间（EVN-2，缺省回退 MSH-7）
 */
public record PatientUpdate(String messageControlId,
                            String patientId,
                            String patientName,
                            Instant eventTime) {
}
