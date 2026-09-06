package org.dataagent.clean.pipeline.validate;

import org.dataagent.clean.pipeline.model.profile.ProfileDiff;
import org.dataagent.clean.toolsclient.model.ProfileResponse;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 构造处理前后的画像 diff，常识级校验的唯一输入。所有派生指标都由代码计算，
 * 给 LLM 的是「人均入院次数从 1.36 变成 1.00，临床上合理吗」这样的问题。
 */
@Component
public class ProfileDiffBuilder {

    /** 标识列名，用于计算人均入院次数 */
    private static final String COL_PATIENT = "患者ID";
    private static final String COL_ADMISSION = "住院ID";

    public ProfileDiff build(ProfileResponse before, ProfileResponse after) {
        ProfileDiff diff = new ProfileDiff();
        diff.setRowCountBefore(orZero(before.getRowCount()));
        diff.setRowCountAfter(orZero(after.getRowCount()));

        Map<String, ProfileResponse.ColumnProfile> afterColumns = index(after);
        for (ProfileResponse.ColumnProfile column : before.getColumns()) {
            ProfileResponse.ColumnProfile counterpart = afterColumns.get(column.getName());
            if (counterpart == null) {
                continue;
            }
            diff.getColumns().put(column.getName(), toDelta(column, counterpart));
        }

        computeDerived(diff, before, after);
        return diff;
    }

    /**
     * 派生指标。人均入院次数在真实数据里约 1.36，列错位缺陷会让它塌成 1.00，
     * 而 1.00 在任何统计检查里都正常，只有临床常识能发现。
     */
    private void computeDerived(ProfileDiff diff, ProfileResponse before, ProfileResponse after) {
        Long patientsBefore = distinctOf(before, COL_PATIENT);
        Long admissionsBefore = distinctOf(before, COL_ADMISSION);
        Long patientsAfter = distinctOf(after, COL_PATIENT);
        Long admissionsAfter = distinctOf(after, COL_ADMISSION);

        if (patientsBefore != null && admissionsBefore != null && patientsBefore > 0) {
            diff.putDerived("admissionsPerPatientBefore",
                round(admissionsBefore.doubleValue() / patientsBefore));
        }
        if (patientsAfter != null && admissionsAfter != null && patientsAfter > 0) {
            diff.putDerived("admissionsPerPatientAfter",
                round(admissionsAfter.doubleValue() / patientsAfter));
        }
        if (patientsAfter != null && patientsAfter > 0) {
            diff.putDerived("recordsPerPatientAfter",
                round((double) diff.getRowCountAfter() / patientsAfter));
        }
        if (diff.getRowCountBefore() > 0) {
            diff.putDerived("rowRetentionRatio",
                round((double) diff.getRowCountAfter() / diff.getRowCountBefore()));
        }
        diff.putDerived("rowsRemoved", diff.getRowCountBefore() - diff.getRowCountAfter());
    }

    private ProfileDiff.ColumnDelta toDelta(ProfileResponse.ColumnProfile before,
                                            ProfileResponse.ColumnProfile after) {
        ProfileDiff.ColumnDelta delta = new ProfileDiff.ColumnDelta();
        delta.setName(before.getName());
        delta.setMissingRateBefore(before.getMissingRate());
        delta.setMissingRateAfter(after.getMissingRate());
        delta.setDistinctBefore(before.getDistinctCount());
        delta.setDistinctAfter(after.getDistinctCount());
        if (before.getNumeric() != null) {
            delta.setMinBefore(before.getNumeric().getMin());
            delta.setMaxBefore(before.getNumeric().getMax());
        }
        if (after.getNumeric() != null) {
            delta.setMinAfter(after.getNumeric().getMin());
            delta.setMaxAfter(after.getNumeric().getMax());
        }
        return delta;
    }

    private Map<String, ProfileResponse.ColumnProfile> index(ProfileResponse profile) {
        Map<String, ProfileResponse.ColumnProfile> index = new LinkedHashMap<>();
        profile.getColumns().forEach(column -> index.put(column.getName(), column));
        return index;
    }

    private Long distinctOf(ProfileResponse profile, String columnName) {
        return profile.getColumns().stream()
            .filter(column -> columnName.equals(column.getName()))
            .map(ProfileResponse.ColumnProfile::getDistinctCount)
            .findFirst()
            .orElse(null);
    }

    private long orZero(Long value) {
        return value == null ? 0L : value;
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
