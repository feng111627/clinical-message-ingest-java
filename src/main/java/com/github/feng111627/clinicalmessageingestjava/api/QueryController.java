package com.github.feng111627.clinicalmessageingestjava.api;

import com.github.feng111627.clinicalmessageingestjava.store.ConflictRecord;
import com.github.feng111627.clinicalmessageingestjava.store.ConflictStore;
import com.github.feng111627.clinicalmessageingestjava.store.MessageRecord;
import com.github.feng111627.clinicalmessageingestjava.store.MessageStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 本地处理结论与冲突留痕的查询入口，便于对接排查。 */
@RestController
@RequestMapping("/api")
public class QueryController {

    private final MessageStore messageStore;
    private final ConflictStore conflictStore;

    public QueryController(MessageStore messageStore, ConflictStore conflictStore) {
        this.messageStore = messageStore;
        this.conflictStore = conflictStore;
    }

    /** 按消息控制标识查询处理结论。 */
    @GetMapping("/messages/{controlId}")
    public ResponseEntity<MessageRecord> byControlId(@PathVariable String controlId) {
        return messageStore.findByControlId(controlId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** 按患者标识查询相关消息的处理结论。 */
    @GetMapping("/messages")
    public List<MessageRecord> byPatientId(@RequestParam String patientId) {
        return messageStore.findByPatientId(patientId);
    }

    /** 查询更新冲突留痕，可按患者过滤。 */
    @GetMapping("/conflicts")
    public List<ConflictRecord> conflicts(@RequestParam(required = false) String patientId) {
        return patientId == null ? conflictStore.findAll() : conflictStore.findByPatientId(patientId);
    }
}
