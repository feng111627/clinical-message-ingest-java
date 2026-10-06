package com.github.feng111627.clinicalmessageingestjava.api;

import com.github.feng111627.clinicalmessageingestjava.ingest.BatchIngestResult;
import com.github.feng111627.clinicalmessageingestjava.ingest.IngestResult;
import com.github.feng111627.clinicalmessageingestjava.ingest.MessageIngestService;
import com.github.feng111627.clinicalmessageingestjava.store.ConflictRecord;
import com.github.feng111627.clinicalmessageingestjava.store.ConflictStore;
import com.github.feng111627.clinicalmessageingestjava.store.MessageRecord;
import com.github.feng111627.clinicalmessageingestjava.store.MessageStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** HL7 消息接入与本地排查查询的 REST 入口。 */
@RestController
@RequestMapping("/api/messages")
public class IngestController {

    private final MessageIngestService ingestService;
    private final MessageStore messageStore;
    private final ConflictStore conflictStore;

    public IngestController(MessageIngestService ingestService, MessageStore messageStore,
                            ConflictStore conflictStore) {
        this.ingestService = ingestService;
        this.messageStore = messageStore;
        this.conflictStore = conflictStore;
    }

    /** 单条接入。 */
    @PostMapping(consumes = "text/plain")
    public IngestResult ingest(@RequestBody String rawMessage) {
        return ingestService.ingest(rawMessage);
    }

    /** 批量接入：一次一整批；超容量上限整批拒收（413），不处理任何一条。 */
    @PostMapping(value = "/batch", consumes = "application/json")
    public ResponseEntity<BatchIngestResult> ingestBatch(@RequestBody List<String> rawMessages) {
        BatchIngestResult result = ingestService.ingestBatch(rawMessages);
        return result.accepted()
                ? ResponseEntity.ok(result)
                : ResponseEntity.status(413).body(result);
    }

    /** 按消息编号查询处理结论（对接排查用）。 */
    @GetMapping("/{controlId}")
    public ResponseEntity<List<MessageRecord>> byControlId(@PathVariable String controlId) {
        List<MessageRecord> records = messageStore.findByControlId(controlId);
        return records.isEmpty() ? ResponseEntity.notFound().build() : ResponseEntity.ok(records);
    }

    /** 按患者标识查询处理结论。 */
    @GetMapping(params = "patientId")
    public List<MessageRecord> byPatient(@RequestParam String patientId) {
        return messageStore.findByPatient(patientId);
    }

    /** 按患者或消息编号查询更新冲突痕迹（人工核对用）。 */
    @GetMapping("/conflicts")
    public List<ConflictRecord> conflicts(@RequestParam(required = false) String patientId,
                                          @RequestParam(required = false) String controlId) {
        if (patientId != null) {
            return conflictStore.findByPatient(patientId);
        }
        if (controlId != null) {
            return conflictStore.findByControlId(controlId);
        }
        return conflictStore.all();
    }
}
