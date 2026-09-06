package org.dataagent.clean.pipeline.eval;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单个用例的评分结果。检出情况拆成 detected / missed / falseAlarm 三个集合而非
 * 单一命中率，便于定位具体问题。
 */
@Data
public class CaseScore {

    private String caseId;

    /** L1_RULE / L2_STRUCTURE / L3_JUDGMENT / L4_DISCOVERY */
    private String caseLevel;

    private String taskCode;

    // ── 执行 ──
    /** 是否真的发起过沙箱执行。执行成功率的分母是它，不是用例总数。 */
    private boolean execAttempted;

    private boolean execSuccess;

    private int repairAttempts;

    /** 本用例里因「修复版与已跑过的版本相同」而提前终止的步骤数。 */
    private int repairShortCircuited;

    // ── 结果正确性 ──
    /**
     * 本用例是否计入结果正确率。expectClarify=true 的用例不计（正确行为是停下
     * 提问，而澄清答复回传接口尚未实现）。
     */
    private boolean resultScorable;

    private boolean resultCorrect;

    /** 该用例需求真正指向的缺陷，「码@列」形式（golden 中 userAsked=true 的那些）。 */
    private Set<String> targetCodes = new LinkedHashSet<>();

    /** 处理后仍被检出的目标缺陷。 */
    private Set<String> residualTargets = new LinkedHashSet<>();

    // ── 澄清 ──
    private boolean clarifyExpected;

    private boolean clarifyActual;

    private List<String> clarifyTopics = new ArrayList<>();

    // ── 检出 ──
    private Set<String> detected = new LinkedHashSet<>();

    private Set<String> missed = new LinkedHashSet<>();

    private Set<String> falseAlarm = new LinkedHashSet<>();

    /** 每个注入缺陷一个样本，分层检出率按 expectLevel 分组统计。 */
    private List<DefectSample> samples = new ArrayList<>();

    /** 没能归一到任何缺陷码的常识级疑点原文，见 {@link DefectCodeMatcher}。 */
    private List<String> unmatchedSuspicions = new ArrayList<>();

    /**
     * 检出但属于合成基线自带、非注入的信号（升压药高缺失、意识列 TWD）。不计误报，
     * 单独计数。
     */
    private Set<String> baselineInherent = new LinkedHashSet<>();

    // ── L4 主动发现 ──
    private List<String> proactiveExpected = new ArrayList<>();

    private List<String> proactiveHit = new ArrayList<>();

    // ── 归因 ──
    private FailureCategory failureCategory = FailureCategory.OK;

    private String failureDetail;

    private long costMillis;

    /**
     * 一个注入缺陷的检出样本。
     *
     * @param code       缺陷码
     * @param expectLevel ROW / DISTRIBUTION / STRUCTURE / COMMON_SENSE / CLARIFY
     * @param userAsked  用户是否会在需求里主动提到；false 的进「问题发现率」
     * @param hit        系统是否报告了它
     */
    public record DefectSample(String code, String expectLevel, boolean userAsked, boolean hit) {
    }
}
