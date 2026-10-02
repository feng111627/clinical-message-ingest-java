package com.github.feng111627.clinicalmessageingestjava.hl7;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HL7 TS 时间格式解析：YYYY[MM[DD[HH[MM[SS[.ffff]]]]]][+/-HHMM]。
 * 缺省精度按最小值补齐（月/日为 1，时分秒为 0）；无时区时按 UTC 处理，
 * 保证同一批消息在不同到达顺序下比较结果一致。
 */
public final class Hl7Timestamp {

    private static final Pattern TS = Pattern.compile(
            "^(\\d{4})(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?(\\.\\d{1,6})?([+-]\\d{4})?$");

    private Hl7Timestamp() {
    }

    /**
     * 解析 HL7 TS 字符串为 Instant。
     *
     * @throws Hl7ParseException 格式非法时抛出，原因为 {@link RejectReason#INVALID_TIMESTAMP}
     */
    public static Instant parse(String fieldName, String value) throws Hl7ParseException {
        Matcher m = TS.matcher(value == null ? "" : value.trim());
        if (!m.matches()) {
            throw new Hl7ParseException(RejectReason.INVALID_TIMESTAMP,
                    fieldName + " 时间格式非法: '" + value + "'，期望 YYYY[MM[DD[HH[MM[SS]]]]][+/-HHMM]");
        }
        int year = Integer.parseInt(m.group(1));
        int month = num(m.group(2), 1);
        int day = num(m.group(3), 1);
        int hour = num(m.group(4), 0);
        int minute = num(m.group(5), 0);
        int second = num(m.group(6), 0);
        int nano = 0;
        if (m.group(7) != null) {
            String frac = (m.group(7).substring(1) + "000000000").substring(0, 9);
            nano = Integer.parseInt(frac);
        }
        ZoneOffset offset = ZoneOffset.UTC;
        if (m.group(8) != null) {
            offset = ZoneOffset.of(m.group(8).substring(0, 3) + ":" + m.group(8).substring(3));
        }
        try {
            return LocalDateTime.of(year, month, day, hour, minute, second, nano)
                    .toInstant(offset);
        } catch (RuntimeException e) {
            throw new Hl7ParseException(RejectReason.INVALID_TIMESTAMP,
                    fieldName + " 时间取值非法: '" + value + "' (" + e.getMessage() + ")");
        }
    }

    private static int num(String group, int fallback) {
        return group == null ? fallback : Integer.parseInt(group);
    }
}
