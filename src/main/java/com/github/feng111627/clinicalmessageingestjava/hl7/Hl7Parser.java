package com.github.feng111627.clinicalmessageingestjava.hl7;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * HL7v2 竖线分隔（pipe-delimited）格式解析器。
 * 仅依赖 MSH 声明的分隔符与编码字符，不依赖任何外部服务。
 */
@Component
public class Hl7Parser {

    /** 消息内必填字段：段名 -> 必填字段序号。 */
    private static final String[][] REQUIRED_FIELDS = {
            {"MSH-7"},  // 消息时间
            {"MSH-9"},  // 消息类型
            {"MSH-10"}, // 消息控制标识
            {"PID-3"},  // 患者标识
    };

    public Hl7Message parse(String raw) throws Hl7ParseException {
        if (raw == null || raw.isBlank()) {
            throw new Hl7ParseException(ParseFailureReason.EMPTY_MESSAGE, "消息为空");
        }
        // 段以 \r 分隔，兼容 \n
        String[] lines = raw.strip().split("[\\r\\n]+");
        String mshLine = lines[0];
        if (!mshLine.startsWith("MSH")) {
            throw new Hl7ParseException(ParseFailureReason.MISSING_MSH_SEGMENT,
                    "消息必须以 MSH 段开头");
        }
        if (mshLine.length() < 4) {
            throw new Hl7ParseException(ParseFailureReason.BAD_SEGMENT_STRUCTURE,
                    "MSH 段缺少字段分隔符");
        }
        char fieldSep = mshLine.charAt(3);
        if (Character.isLetterOrDigit(fieldSep)) {
            throw new Hl7ParseException(ParseFailureReason.BAD_SEGMENT_STRUCTURE,
                    "MSH 字段分隔符非法: '" + fieldSep + "'");
        }

        List<Hl7Segment> segments = new ArrayList<>();
        // MSH 段特殊处理：MSH-1 即分隔符本身
        List<String> mshFields = new ArrayList<>();
        mshFields.add(String.valueOf(fieldSep));
        for (String f : splitFields(mshLine.substring(4), fieldSep)) {
            mshFields.add(unescape(f));
        }
        segments.add(new Hl7Segment("MSH", mshFields));

        String encodingChars = segments.get(0).field(2);
        if (encodingChars.length() < 4) {
            throw new Hl7ParseException(ParseFailureReason.BAD_SEGMENT_STRUCTURE,
                    "MSH-2 编码字符不完整（需要组件/重复/转义/子组件 4 个字符）: '" + encodingChars + "'");
        }

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.length() < 4 || line.indexOf(fieldSep) < 0) {
                throw new Hl7ParseException(ParseFailureReason.BAD_SEGMENT_STRUCTURE,
                        "段结构错误（段名后缺少字段分隔符）: '" + line + "'");
            }
            String name = line.substring(0, line.indexOf(fieldSep));
            if (!name.matches("[A-Z0-9]{3}")) {
                throw new Hl7ParseException(ParseFailureReason.BAD_SEGMENT_STRUCTURE,
                        "段名非法: '" + name + "'");
            }
            List<String> fields = new ArrayList<>();
            for (String f : splitFields(line.substring(name.length() + 1), fieldSep)) {
                fields.add(unescape(f));
            }
            segments.add(new Hl7Segment(name, fields));
        }

        Hl7Message message = new Hl7Message(segments);
        validateRequired(message);
        validateTimestamp(message.segment("MSH").field(7));
        return message;
    }

    private void validateRequired(Hl7Message message) throws Hl7ParseException {
        for (String[] req : REQUIRED_FIELDS) {
            String[] parts = req[0].split("-");
            Hl7Segment seg = message.segment(parts[0]);
            if (seg == null) {
                throw new Hl7ParseException(ParseFailureReason.MISSING_REQUIRED_FIELD,
                        "缺少必填段 " + parts[0] + "（需要字段 " + req[0] + "）");
            }
            if (seg.field(Integer.parseInt(parts[1])).isBlank()) {
                throw new Hl7ParseException(ParseFailureReason.MISSING_REQUIRED_FIELD,
                        "必填字段缺失: " + req[0]);
            }
        }
    }

    private void validateTimestamp(String msh7) throws Hl7ParseException {
        try {
            parseTimestamp(msh7);
        } catch (IllegalArgumentException e) {
            throw new Hl7ParseException(ParseFailureReason.INVALID_TIMESTAMP,
                    "MSH-7 时间格式非法: '" + msh7 + "'（支持 yyyyMMdd[HHmm[ss]]）");
        }
    }

    /** 解析 HL7 时间戳（yyyyMMdd / yyyyMMddHHmm / yyyyMMddHHmmss）。 */
    public static LocalDateTime parseTimestamp(String ts) {
        if (ts == null || !ts.matches("\\d{8}|\\d{12}|\\d{14}")) {
            throw new IllegalArgumentException("非法时间戳: " + ts);
        }
        String padded = switch (ts.length()) {
            case 8 -> ts + "000000";
            case 12 -> ts + "00";
            default -> ts;
        };
        try {
            return LocalDateTime.parse(padded, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("非法时间戳: " + ts, e);
        }
    }

    private String[] splitFields(String s, char sep) {
        return s.split(java.util.regex.Pattern.quote(String.valueOf(sep)), -1);
    }

    /** 反转义 HL7 标准转义序列（\F\ \S\ \R\ \E\ \T\）。 */
    static String unescape(String value) {
        if (value == null || value.indexOf('\\') < 0) {
            return value;
        }
        return value
                .replace("\\F\\", "|")
                .replace("\\S\\", "^")
                .replace("\\R\\", "~")
                .replace("\\E\\", "\\")
                .replace("\\T\\", "&");
    }
}
