package com.github.feng111627.clinicalmessageingestjava.api;

import com.github.feng111627.clinicalmessageingestjava.ingest.IngestResult;
import com.github.feng111627.clinicalmessageingestjava.ingest.IngestService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息接入 HTTP 接口。
 */
@RestController
@RequestMapping("/api/messages")
public class IngestController {

    private final IngestService ingestService;

    public IngestController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping(consumes = "text/plain")
    public IngestResult ingest(@RequestBody String rawMessage) {
        return ingestService.ingest(rawMessage);
    }
}
