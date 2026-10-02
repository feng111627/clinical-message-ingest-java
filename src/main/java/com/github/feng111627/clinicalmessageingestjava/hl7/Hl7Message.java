package com.github.feng111627.clinicalmessageingestjava.hl7;

import java.util.List;

/** 解析后的 HL7 消息。 */
public record Hl7Message(List<Hl7Segment> segments) {

    /** 取第一个指定名称的段，不存在返回 null。 */
    public Hl7Segment segment(String name) {
        for (Hl7Segment s : segments) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        return null;
    }
}
