package com.github.feng111627.clinicalmessageingestjava.hl7;

public class Hl7ParseException extends Exception {
    private final ParseFailureReason reason;

    public Hl7ParseException(ParseFailureReason reason, String detail) {
        super(detail);
        this.reason = reason;
    }

    public ParseFailureReason getReason() {
        return reason;
    }
}
