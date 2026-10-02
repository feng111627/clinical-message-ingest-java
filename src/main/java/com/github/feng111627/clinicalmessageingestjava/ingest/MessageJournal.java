package com.github.feng111627.clinicalmessageingestjava.ingest;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已处理消息日志：以消息控制标识（MSH-10）为键记录内容指纹，
 * 用于幂等判定——区分"同一消息原样重发"与"同一标识但内容不同的冲突重发"。
 */
@Component
public class MessageJournal {

    /**
     * 已处理消息的内容指纹，用于判断重发内容是否一致。
     */
    public record Fingerprint(String patientId, String patientName, String eventTime) {

        static Fingerprint of(PatientUpdate update) {
            return new Fingerprint(update.patientId(), update.patientName(),
                    update.eventTime().toString());
        }

        boolean matches(PatientUpdate update) {
            return equals(of(update));
        }
    }

    private final Map<String, Fingerprint> processed = new ConcurrentHashMap<>();

    /**
     * 查询某消息控制标识是否已处理过。
     */
    public Optional<Fingerprint> find(String messageControlId) {
        return Optional.ofNullable(processed.get(messageControlId));
    }

    /**
     * 记录一条已处理消息（仅当标识未被占用时生效）。
     *
     * @return true 表示本次登记成功（是新消息）；false 表示标识已存在
     */
    public boolean recordIfAbsent(PatientUpdate update) {
        return processed.putIfAbsent(update.messageControlId(), Fingerprint.of(update)) == null;
    }

    public int count() {
        return processed.size();
    }
}
