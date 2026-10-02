package com.github.feng111627.clinicalmessageingestjava.hl7;

import java.util.List;
import java.util.Optional;

/**
 * 解析后的 HL7v2 消息：段列表 + 分隔符信息。
 */
public final class Hl7Message {

    private final List<Segment> segments;

    public Hl7Message(List<Segment> segments) {
        this.segments = List.copyOf(segments);
    }

    public List<Segment> getSegments() {
        return segments;
    }

    public Optional<Segment> firstSegment(String name) {
        return segments.stream().filter(s -> s.getName().equals(name)).findFirst();
    }

    /**
     * 一个段：段名 + 字段列表（字段下标从 1 开始，与 HL7 习惯一致）。
     */
    public static final class Segment {

        private final String name;
        private final List<String> fields;

        public Segment(String name, List<String> fields) {
            this.name = name;
            this.fields = List.copyOf(fields);
        }

        public String getName() {
            return name;
        }

        /**
         * 取字段值，下标从 1 开始；越界或空串返回 empty。
         */
        public Optional<String> field(int index) {
            if (index < 1 || index > fields.size()) {
                return Optional.empty();
            }
            String v = fields.get(index - 1);
            return v.isEmpty() ? Optional.empty() : Optional.of(v);
        }
    }
}
