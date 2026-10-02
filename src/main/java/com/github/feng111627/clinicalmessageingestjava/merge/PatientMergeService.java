package com.github.feng111627.clinicalmessageingestjava.merge;

import com.github.feng111627.clinicalmessageingestjava.store.PatientStore;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 患者信息按业务时间有序合并。
 * 规则：
 * - 业务时间更新或相同 -> 覆盖（APPLIED）；
 * - 业务时间更旧 -> 忽略（IGNORED_STALE），旧数据不得反盖新数据；
 * - 同一本地病案号对应到不同的完整患者标识（不同分配机构）-> 冲突（CONFLICT_PATIENT_ID）。
 */
@Service
public class PatientMergeService {

    private final PatientStore patientStore;

    public PatientMergeService(PatientStore patientStore) {
        this.patientStore = patientStore;
    }

    public MergeOutcome merge(String patientId, String name, String gender, LocalDateTime businessTime) {
        String localId = localIdOf(patientId);
        // 冲突检测：本地病案号相同但完整标识不同
        for (var existing : allPatientIds()) {
            if (!existing.equals(patientId) && localIdOf(existing).equals(localId)) {
                return MergeOutcome.CONFLICT_PATIENT_ID;
            }
        }
        PatientRecord record = patientStore.findOrCreate(patientId);
        return record.apply(name, gender, businessTime)
                ? MergeOutcome.APPLIED
                : MergeOutcome.IGNORED_STALE;
    }

    private java.util.Set<String> allPatientIds() {
        // PatientStore 仅暴露按 id 查询；这里通过内部快照遍历
        return patientStore.snapshotIds();
    }

    /** 取 PID-3（CX）的本地病案号部分，即第一个 '^' 之前。 */
    static String localIdOf(String patientId) {
        int idx = patientId.indexOf('^');
        return idx < 0 ? patientId : patientId.substring(0, idx);
    }
}
