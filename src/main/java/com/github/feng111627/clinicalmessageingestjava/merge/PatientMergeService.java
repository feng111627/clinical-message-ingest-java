package com.github.feng111627.clinicalmessageingestjava.merge;

import com.github.feng111627.clinicalmessageingestjava.store.PatientStore;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 患者信息按业务时间有序合并。
 * 规则：
 * - 业务时间更新 -> 覆盖（APPLIED）；
 * - 业务时间更旧 -> 忽略（IGNORED_STALE），旧数据不得反盖新数据；
 * - 业务时间相同但内容对不上 -> 更新冲突（CONFLICT_SAME_TIME），已有数据保持不动；
 * - 同一本地病案号对应到不同的完整患者标识（不同分配机构）-> 冲突（CONFLICT_PATIENT_ID）。
 *
 * 方法级同步：保证同一患者并发更新时判定与应用是原子的，
 * 不会出现旧数据反盖新数据。
 */
@Service
public class PatientMergeService {

    private final PatientStore patientStore;

    public PatientMergeService(PatientStore patientStore) {
        this.patientStore = patientStore;
    }

    public synchronized MergeResult merge(String patientId, String name, String gender,
                                          LocalDateTime businessTime, String controlId) {
        String localId = localIdOf(patientId);
        // 冲突检测：本地病案号相同但完整标识不同
        for (var existing : patientStore.snapshotIds()) {
            if (!existing.equals(patientId) && localIdOf(existing).equals(localId)) {
                return MergeResult.of(MergeOutcome.CONFLICT_PATIENT_ID);
            }
        }
        PatientRecord record = patientStore.findOrCreate(patientId);
        PatientRecord.ApplyResult applied = record.apply(name, gender, businessTime, controlId);
        return switch (applied.outcome()) {
            case APPLIED -> MergeResult.of(MergeOutcome.APPLIED);
            case STALE -> MergeResult.of(MergeOutcome.IGNORED_STALE);
            case CONFLICT -> MergeResult.conflict(applied.differingFields(), record.getLastControlId());
        };
    }

    /** 取 PID-3（CX）的本地病案号部分，即第一个 '^' 之前。 */
    static String localIdOf(String patientId) {
        int idx = patientId.indexOf('^');
        return idx < 0 ? patientId : patientId.substring(0, idx);
    }
}
