package com.github.feng111627.clinicalmessageingestjava.hl7;

/**
 * HL7 解析失败异常，携带可区分的拒收原因。
 */
public class Hl7ParseException extends Exception {

    private final RejectReason reason;

    public Hl7ParseException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
