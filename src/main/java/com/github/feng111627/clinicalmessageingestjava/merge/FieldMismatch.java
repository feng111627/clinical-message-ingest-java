package com.github.feng111627.clinicalmessageingestjava.merge;

/** 同一业务时间下，单个字段的内容不一致痕迹。 */
public record FieldMismatch(String field, String existingValue, String incomingValue) {
}
