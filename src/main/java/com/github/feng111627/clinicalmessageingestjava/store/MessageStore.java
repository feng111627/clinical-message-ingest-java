package com.github.feng111627.clinicalmessageingestjava.store;

import org.springframework.stereotype.Repository;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 已见消息控制标识的本地存储（幂等依据），仅内存，不依赖外部服务。 */
@Repository
public class MessageStore {

    private final Set<String> seenControlIds = ConcurrentHashMap.newKeySet();

    /** 若控制标识未见过则记录并返回 true；已见过（重复投递）返回 false。 */
    public boolean recordIfAbsent(String messageControlId) {
        return seenControlIds.add(messageControlId);
    }

    public boolean hasSeen(String messageControlId) {
        return seenControlIds.contains(messageControlId);
    }

    public int size() {
        return seenControlIds.size();
    }
}
