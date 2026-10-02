package com.github.feng111627.clinicalmessageingestjava.hl7;

import java.util.List;

/**
 * 一个 HL7 段：段名 + 字段列表。
 * fields 下标 0 对应 HL7 字段序号 1；对 MSH 段，下标 0 即字段分隔符本身（MSH-1）。
 */
public record Hl7Segment(String name, List<String> fields) {

    /** 取 HL7 字段序号 n（1 基）的值，缺失返回空串。 */
    public String field(int n) {
        int idx = n - 1;
        if (idx < 0 || idx >= fields.size()) {
            return "";
        }
        String v = fields.get(idx);
        return v == null ? "" : v;
    }
}
