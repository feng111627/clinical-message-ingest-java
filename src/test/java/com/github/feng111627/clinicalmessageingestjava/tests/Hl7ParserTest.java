package com.github.feng111627.clinicalmessageingestjava.tests;

import com.github.feng111627.clinicalmessageingestjava.hl7.Hl7Message;
import com.github.feng111627.clinicalmessageingestjava.hl7.Hl7ParseException;
import com.github.feng111627.clinicalmessageingestjava.hl7.Hl7Parser;
import com.github.feng111627.clinicalmessageingestjava.hl7.RejectReason;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HL7 解析器单元测试：段/字段/转义识别与各类畸形消息的可区分拒收。
 */
class Hl7ParserTest {

    private static final Logger log = LoggerFactory.getLogger(Hl7ParserTest.class);

    private final Hl7Parser parser = new Hl7Parser();

    private static final String VALID =
            "MSH|^~\\&|HIS|HOSPITAL|RCV|HOSPITAL|20240101103000||ADT^A01|MSG00001|P|2.5\r"
                    + "EVN|A01|20240101100000\r"
                    + "PID|1||P12345^^^HIS||Zhang^San";

    @Test
    void parsesSegmentsAndFields() throws Exception {
        Hl7Message msg = parser.parse(VALID);
        assertEquals(3, msg.getSegments().size());
        var msh = msg.firstSegment("MSH").orElseThrow();
        assertEquals("|", msh.field(1).orElseThrow());
        assertEquals("^~\\&", msh.field(2).orElseThrow());
        assertEquals("MSG00001", msh.field(10).orElseThrow());
        assertEquals("P12345^^^HIS", msg.firstSegment("PID").orElseThrow().field(3).orElseThrow());
        log.info("parse ok: controlId={}", msh.field(10).orElseThrow());
    }

    @Test
    void unescapesStandardEscapeSequences() throws Exception {
        String raw = VALID.replace("Zhang^San", "Zhang\\F\\Li\\S\\Si\\E\\");
        Hl7Message msg = parser.parse(raw);
        String name = msg.firstSegment("PID").orElseThrow().field(5).orElseThrow();
        assertEquals("Zhang|Li^Si\\", name);
        log.info("escape ok: PID-5={}", name);
    }

    @Test
    void rejectsWhenMshMissing() {
        Hl7ParseException e = assertThrows(Hl7ParseException.class,
                () -> parser.parse("PID|1||P12345||Zhang^San"));
        log.info("reject: reason={} detail={}", e.getReason(), e.getMessage());
        assertEquals(RejectReason.MISSING_SEGMENT, e.getReason());
    }

    @Test
    void rejectsBlankMessage() {
        Hl7ParseException e = assertThrows(Hl7ParseException.class, () -> parser.parse("  \n "));
        log.info("reject: reason={} detail={}", e.getReason(), e.getMessage());
        assertEquals(RejectReason.MALFORMED_SEGMENT, e.getReason());
    }

    @Test
    void rejectsBadSegmentName() {
        Hl7ParseException e = assertThrows(Hl7ParseException.class,
                () -> parser.parse(VALID + "\r!bad|segment"));
        log.info("reject: reason={} detail={}", e.getReason(), e.getMessage());
        assertEquals(RejectReason.MALFORMED_SEGMENT, e.getReason());
    }

    @Test
    void rejectsIncompleteMshEncodingChars() {
        Hl7ParseException e = assertThrows(Hl7ParseException.class,
                () -> parser.parse("MSH|^~|HIS\rPID|1||P1||A^B"));
        log.info("reject: reason={} detail={}", e.getReason(), e.getMessage());
        assertEquals(RejectReason.MALFORMED_SEGMENT, e.getReason());
    }
}
