package com.github.feng111627.clinicalmessageingestjava.hl7;

/** 解析/校验失败的可区分原因。 */
public enum ParseFailureReason {
    EMPTY_MESSAGE,
    MISSING_MSH_SEGMENT,
    BAD_SEGMENT_STRUCTURE,
    MISSING_REQUIRED_FIELD,
    INVALID_TIMESTAMP
}
