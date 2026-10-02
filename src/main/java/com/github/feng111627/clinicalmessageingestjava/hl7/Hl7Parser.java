package com.github.feng111627.clinicalmessageingestjava.hl7;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * HL7v2 竖线分隔（pipe-delimited）格式解析器。
 *
 * <p>支持范围：</p>
 * <ul>
 *   <li>段分隔符：\r、\n 或 \r\n</li>
 *   <li>字段分隔符：仅支持竖线 '|'（由 MSH-1 声明）</li>
 *   <li>编码字符：MSH-2 声明的 4 个字符（通常为 ^~\&amp;）</li>
 *   <li>转义序列：\F\ \S\ \R\ \E\ \T\，未知转义原样保留</li>
 * </ul>
 */
@Component
public class Hl7Parser {

    private static final Pattern SEGMENT_NAME = Pattern.compile("^[A-Z][A-Z0-9]{2}$");

    /**
     * 解析原始消息文本。
     *
     * @param raw 原始 HL7 消息
     * @return 解析后的消息
     * @throws Hl7ParseException 结构错误时抛出，原因见 {@link RejectReason}
     */
    public Hl7Message parse(String raw) throws Hl7ParseException {
        if (raw == null || raw.isBlank()) {
            throw new Hl7ParseException(RejectReason.MALFORMED_SEGMENT, "消息为空");
        }

        String[] lines = raw.split("\r\n|\r|\n");
        List<Hl7Message.Segment> segments = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            segments.add(parseSegment(line));
        }
        if (segments.isEmpty()) {
            throw new Hl7ParseException(RejectReason.MALFORMED_SEGMENT, "消息不包含任何段");
        }
        if (!segments.get(0).getName().equals("MSH")) {
            throw new Hl7ParseException(RejectReason.MISSING_SEGMENT,
                    "首段必须是 MSH，实际为 " + segments.get(0).getName());
        }
        return new Hl7Message(segments);
    }

    private Hl7Message.Segment parseSegment(String line) throws Hl7ParseException {
        if (line.startsWith("MSH")) {
            return parseMsh(line);
        }
        if (line.length() < 3 || !SEGMENT_NAME.matcher(line.substring(0, 3)).matches()) {
            throw new Hl7ParseException(RejectReason.MALFORMED_SEGMENT,
                    "段名非法: " + (line.length() <= 12 ? line : line.substring(0, 12) + "..."));
        }
        String name = line.substring(0, 3);
        String rest = line.length() > 3 ? line.substring(3) : "";
        if (!rest.isEmpty() && !rest.startsWith("|")) {
            throw new Hl7ParseException(RejectReason.MALFORMED_SEGMENT,
                    "段 " + name + " 的段名后必须是字段分隔符 '|'");
        }
        List<String> fields = splitFields(rest.isEmpty() ? "" : rest.substring(1));
        return new Hl7Message.Segment(name, fields);
    }

    /**
     * MSH 是特殊段：MSH-1 是字段分隔符本身，MSH-2 是编码字符。
     */
    private Hl7Message.Segment parseMsh(String line) throws Hl7ParseException {
        if (line.length() < 4 || line.charAt(3) != '|') {
            throw new Hl7ParseException(RejectReason.MALFORMED_SEGMENT,
                    "仅支持竖线分隔格式，MSH-1 字段分隔符必须是 '|'");
        }
        // MSH|^~\&|... —— 编码字符固定占 4 位（下标 4..7），其后是字段分隔符
        if (line.length() < 9 || line.charAt(8) != '|') {
            throw new Hl7ParseException(RejectReason.MALFORMED_SEGMENT,
                    "MSH-2 编码字符缺失或不完整（需要 4 个字符，如 ^~\\&）");
        }
        String encodingChars = line.substring(4, 8);
        List<String> fields = new ArrayList<>();
        fields.add("|");            // MSH-1
        fields.add(encodingChars);  // MSH-2
        fields.addAll(splitFields(line.substring(9))); // MSH-3 起
        return new Hl7Message.Segment("MSH", fields);
    }

    private List<String> splitFields(String s) {
        List<String> fields = new ArrayList<>();
        if (s.isEmpty()) {
            return fields;
        }
        for (String f : s.split("\\|", -1)) {
            fields.add(unescape(f));
        }
        return fields;
    }

    /**
     * 还原 HL7 标准转义序列。
     */
    static String unescape(String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == '\\') {
                int end = value.indexOf('\\', i + 1);
                if (end > i + 1) {
                    String seq = value.substring(i + 1, end);
                    switch (seq) {
                        case "F" -> { sb.append('|'); i = end + 1; continue; }
                        case "S" -> { sb.append('^'); i = end + 1; continue; }
                        case "R" -> { sb.append('~'); i = end + 1; continue; }
                        case "E" -> { sb.append('\\'); i = end + 1; continue; }
                        case "T" -> { sb.append('&'); i = end + 1; continue; }
                        default -> { /* 未知转义原样保留 */ }
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}
