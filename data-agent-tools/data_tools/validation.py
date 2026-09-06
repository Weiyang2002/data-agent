"""确定性校验：行级 + 分布级（三层校验的前两层，第三层常识级在 Java 侧）。

这两层必须是代码而不是 LLM：行级是浮点比较，分布级是 groupby 且需要看全量数据
（违反隐私约束）。LLM 在校验里唯一有价值的位置是第三层的临床常识判断。
"""

from __future__ import annotations

import logging
from pathlib import Path

import pandas as pd

from data_tools.profiling import (
    DetectorThresholds,
    default_thresholds,
    detect_column_anomalies,
    detect_frame_anomalies,
    profile_column,
)
from data_tools.schemas.validate import (
    RowFinding,
    ValidateDistributionRequest,
    ValidateDistributionResponse,
    ValidateRowRequest,
    ValidateRowResponse,
    ValidityRule,
)

logger = logging.getLogger(__name__)


# ── 行级 ──

def validate_row(request: ValidateRowRequest) -> ValidateRowResponse:
    """按传入规则逐列比对。空规则列表直接报错，不返回空结果：「校验了 0 条规则」
    和「校验通过」在响应体上无法区分，知识库没查到规则时应由 Java 侧短路返回
    NO_EVIDENCE。
    """
    if not request.rules:
        raise ValueError(
            "rules 不能为空。知识库未命中时应由 Java 侧短路返回 NO_EVIDENCE，"
            "而不是发起一次「零规则校验」——那会让「没查」看起来像「没问题」"
        )

    path = Path(request.datasetPath)
    if not path.exists():
        raise FileNotFoundError(f"数据集不存在: {request.datasetPath}")

    frame = pd.read_parquet(path)
    findings: list[RowFinding] = []
    for rule in request.rules:
        finding = _check_rule(frame, rule, request.maxSamplesPerRule)
        if finding is not None:
            findings.append(finding)

    logger.info("行级校验完成 path=%s 规则=%d 发现=%d",
                request.datasetPath, len(request.rules), len(findings))
    return ValidateRowResponse(
        datasetPath=str(path),
        totalRows=len(frame),
        checkedRules=len(request.rules),
        findings=findings,
    )


def _check_rule(frame: pd.DataFrame, rule: ValidityRule, max_samples: int) -> RowFinding | None:
    if rule.column not in frame.columns:
        # 列不存在不是「没有违规」，是规则用错了地方，抛出明确错误
        raise ValueError(f"规则 {rule.ruleId} 指向的列不存在: {rule.column}")

    series = frame[rule.column]
    present = series.notna()
    checked = int(present.sum())
    if checked == 0:
        return None

    if rule.allowedValues is not None:
        mask = present & ~series.astype(str).isin(rule.allowedValues)
        detail = f"取值不在允许集合 {rule.allowedValues[:6]} 内"
    else:
        numeric = pd.to_numeric(series, errors="coerce")
        mask = present & numeric.isna()          # 该是数值却解析不出来，也算违规
        if rule.min is not None:
            mask = mask | (present & (numeric < rule.min))
        if rule.max is not None:
            mask = mask | (present & (numeric > rule.max))
        bounds = f"[{rule.min}, {rule.max}]" + (f" {rule.unit}" if rule.unit else "")
        detail = f"超出有效区间 {bounds}"

    violation_count = int(mask.sum())
    if violation_count == 0:
        return None

    sample_index = frame.index[mask][:max_samples]
    samples = series.loc[sample_index]
    return RowFinding(
        ruleId=rule.ruleId,
        column=rule.column,
        violationCount=violation_count,
        violationRatio=round(violation_count / checked, 6),
        checkedCount=checked,
        sampleRowIds=[int(i) for i in sample_index],
        sampleValues=[str(value) for value in samples],
        expectedMin=rule.min,
        expectedMax=rule.max,
        sourceDoc=rule.sourceDoc,
        sourceLocator=rule.sourceLocator,
        evidence=f"{rule.column} 有 {violation_count} 行{detail}"
                 f"（依据：{rule.sourceDoc} {rule.sourceLocator}）".strip(),
    )


# ── 分布级 ──

def validate_distribution(request: ValidateDistributionRequest) -> ValidateDistributionResponse:
    """在数据集上重跑分布级检测器。检测器与 ``/profile`` 共用同一批实现，保证
    处理前后用同一批检测器。
    """
    path = Path(request.datasetPath)
    if not path.exists():
        raise FileNotFoundError(f"数据集不存在: {request.datasetPath}")

    frame = pd.read_parquet(path)
    target_columns = request.columns or [str(name) for name in frame.columns]
    missing = [name for name in target_columns if name not in frame.columns]
    if missing:
        raise ValueError(f"数据集中不存在这些列: {missing}")

    defaults = default_thresholds()
    thresholds = DetectorThresholds(
        timestamp_all_zero=_pick(request.thresholds.timestampAllZeroRatio,
                                 defaults.timestamp_all_zero),
        high_missing=_pick(request.thresholds.highMissingRate, defaults.high_missing),
        null_run_factor=int(_pick(request.thresholds.nullRunFactor, defaults.null_run_factor)),
    )

    findings = [pattern.model_dump() for pattern in detect_frame_anomalies(frame)]
    stats = []
    for name in target_columns:
        column = profile_column(frame, name, request.textValueTopN)
        stats.append({
            "name": column.name,
            "dtype": column.dtype,
            "missingRate": column.missingRate,
            "distinctCount": column.distinctCount,
            "numeric": column.numeric.model_dump() if column.numeric else None,
        })
        # include_row_level=False：日期格式混杂属于行级，分布级重复报会让检出数虚高
        for pattern in detect_column_anomalies(
                frame[name], column, thresholds, include_row_level=False):
            findings.append(pattern.model_dump())

    logger.info("分布级校验完成 path=%s 列=%d 发现=%d",
                request.datasetPath, len(target_columns), len(findings))
    return ValidateDistributionResponse(
        datasetPath=str(path),
        totalRows=len(frame),
        findings=findings,
        columnStats=stats,
    )


def _pick(override, fallback):
    return fallback if override is None else override
