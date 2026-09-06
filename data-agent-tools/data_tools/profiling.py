"""数据画像 + 确定性异常检出。

模块名用 ``profiling`` 而不是 ``profile``（后者会遮蔽标准库的 ``profile`` 模块）。

本模块只做统计和模式匹配，不做业务决策：报告事实（缺失率、占比），不判断缺失
语义、不决定是否澄清。越界判定需要知识库阈值，属于 ``validation.validate_row``，
Python 侧不硬编码任何临床数字。每一条检出都是可复现、可单测、零 Token 的。
"""

from __future__ import annotations

import logging
import re
import time
import unicodedata
from itertools import combinations
from pathlib import Path

import numpy as np
import pandas as pd

from data_tools.config import config_float, config_int
from data_tools.schemas.profile import (
    AnomalyPattern,
    ColumnProfile,
    KeyCandidate,
    NumericProfile,
    ProfileRequest,
    ProfileResponse,
    TextValue,
)

logger = logging.getLogger(__name__)


# 层级常量，与 defects.py 保持同一套命名空间（不 import defects，避免画像模块
# 依赖评测模块的反向依赖）
LEVEL_ROW = "ROW"
LEVEL_DISTRIBUTION = "DISTRIBUTION"
LEVEL_STRUCTURE = "STRUCTURE"
LEVEL_CLARIFY = "CLARIFY"

# 日期格式识别。key 是格式名，value 是整串匹配的正则。只识别不解析。
_DATE_PATTERNS: dict[str, re.Pattern[str]] = {
    "ISO":        re.compile(r"^\d{4}-\d{2}-\d{2}([ T]\d{2}:\d{2}(:\d{2})?)?$"),
    "SLASH_Y4":   re.compile(r"^\d{4}/\d{1,2}/\d{1,2}([ T]\d{2}:\d{2}(:\d{2})?)?$"),
    "SLASH_Y2":   re.compile(r"^\d{2}/\d{1,2}/\d{1,2}([ T]\d{2}:\d{2}(:\d{2})?)?$"),
    "DOT":        re.compile(r"^\d{4}\.\d{1,2}\.\d{1,2}([ T]\d{2}:\d{2}(:\d{2})?)?$"),
    "COMPACT":    re.compile(r"^\d{8}(\d{6})?$"),
}

_PURE_NUMBER = re.compile(r"^-?\d+(\.\d+)?$")

# 主键候选最多组合到几列，不做全组合（组合数爆炸）
_MAX_KEY_COMBINATION = 2
# 参与组合的单列上限
_MAX_SINGLE_COLUMNS = 12
# 主键候选的唯一性门槛，不取 1.0，见 _detect_key_candidates
_KEY_MIN_UNIQUE_RATIO = 0.95


def profile_dataset(request: ProfileRequest) -> ProfileResponse:
    started = time.perf_counter()

    path = Path(request.datasetPath)
    if not path.exists():
        raise FileNotFoundError(f"数据集不存在: {request.datasetPath}")

    # sampleRows 尚未实现，直接报错而不是静默忽略
    if request.sampleRows > 0:
        raise ValueError(
            "sampleRows 尚未实现（M7 上下文策略实验才启用）。"
            "当前返回体不包含任何原始数据行，这是隐私约束的执行点。"
        )

    frame = pd.read_parquet(path)
    columns = [_profile_column(frame, name, request.textValueTopN) for name in frame.columns]
    anomalies = detect_anomalies(frame, columns)
    keys = _detect_key_candidates(frame)

    elapsed = int((time.perf_counter() - started) * 1000)
    logger.info(
        "画像完成 path=%s rows=%d cols=%d 异常模式=%d 耗时=%dms",
        request.datasetPath, len(frame), len(columns), len(anomalies), elapsed,
    )
    return ProfileResponse(
        datasetPath=str(path),
        rowCount=len(frame),
        columnCount=len(frame.columns),
        columns=columns,
        anomalyPatterns=anomalies,
        keyCandidates=keys,
        profileMillis=elapsed,
    )


# ── 单列画像 ──

def _profile_column(frame: pd.DataFrame, name: str, top_n: int) -> ColumnProfile:
    series = frame[name]
    total = len(series)
    non_null = series.dropna()
    missing_rate = round(1.0 - (len(non_null) / total), 6) if total else 0.0
    distinct = int(non_null.nunique())

    numeric: NumericProfile | None = None
    if pd.api.types.is_numeric_dtype(series) and len(non_null) > 0:
        numeric = NumericProfile(
            min=_num(non_null.min()),
            max=_num(non_null.max()),
            mean=_num(non_null.mean()),
            p50=_num(non_null.quantile(0.5)),
            p95=_num(non_null.quantile(0.95)),
            zeroRatio=round(float((non_null == 0).mean()), 6),
        )

    text_values: list[TextValue] | None = None
    truncated = False
    if not pd.api.types.is_numeric_dtype(series) and len(non_null) > 0:
        if distinct <= top_n:
            counts = non_null.astype(str).value_counts()
            text_values = [
                TextValue(value=str(value), count=int(count),
                          ratio=round(int(count) / total, 6))
                for value, count in counts.items()
            ]
        else:
            # 高基数列（如患者ID、住院ID）不枚举取值，标识不进返回体也不进 Prompt
            truncated = True

    return ColumnProfile(
        name=str(name),
        dtype=str(series.dtype),
        missingRate=missing_rate,
        distinctCount=distinct,
        numeric=numeric,
        textValues=text_values,
        textValuesTruncated=truncated,
    )


def _num(value) -> float | None:
    if value is None or pd.isna(value):
        return None
    return round(float(value), 4)


# ── 确定性异常检出 ──

def detect_anomalies(frame: pd.DataFrame, columns: list[ColumnProfile]) -> list[AnomalyPattern]:
    """按固定顺序跑所有检测器。检出的 code 与 ``defects.py`` 的缺陷码同名，评测直接
    比对。

    不在这里做的三类：``OUT_OF_RANGE``（需要知识库阈值，走 ``/validate/row``）；
    ``COLUMN_MISALIGN`` / ``TIME_INVERSION``（常识级，需处理前后画像对比 + 临床
    常识）；``MISSING_SEMANTIC``（Python 只报告缺失率事实，code = ``HIGH_MISSING_RATE``，
    缺失语义由 Java 侧决定是否澄清）。
    """
    thresholds = default_thresholds()
    anomalies: list[AnomalyPattern] = list(detect_frame_anomalies(frame))
    for column in columns:
        anomalies.extend(detect_column_anomalies(
            frame[column.name], column, thresholds, include_row_level=True))
    return anomalies


class DetectorThresholds:
    """检测器阈值。默认值来自 ``data-tools.yaml``，请求可逐项覆盖。这些是统计工程
    判断而非临床规范（临床阈值走知识库、由 Java 传入）。
    """

    def __init__(self, timestamp_all_zero: float, high_missing: float, null_run_factor: int):
        self.timestamp_all_zero = timestamp_all_zero
        self.high_missing = high_missing
        self.null_run_factor = null_run_factor


def default_thresholds() -> DetectorThresholds:
    return DetectorThresholds(
        timestamp_all_zero=config_float("dataTools.profile.timestampAllZeroThreshold",
                                        "DATA_AGENT_TIMESTAMP_ZERO_THRESHOLD", 0.5),
        high_missing=config_float("dataTools.profile.highMissingThreshold",
                                  "DATA_AGENT_HIGH_MISSING_THRESHOLD", 0.9),
        null_run_factor=config_int("dataTools.profile.nullRunFactor",
                                   "DATA_AGENT_NULL_RUN_FACTOR", 5, min_value=2),
    )


def detect_frame_anomalies(frame: pd.DataFrame) -> list[AnomalyPattern]:
    """整表级检测（不针对单列）。"""
    return _detect_row_duplicate(frame)


def detect_column_anomalies(series: pd.Series, column: ColumnProfile,
                            thresholds: DetectorThresholds,
                            include_row_level: bool = True) -> list[AnomalyPattern]:
    """单列检测。``/profile`` 与 ``/validate/distribution`` 共用这一个入口，保证
    处理前后用同一批检测器。
    """
    name = column.name
    found: list[AnomalyPattern] = []
    if include_row_level:
        found.extend(_detect_date_format_mixed(series, name))
    found.extend(_detect_timestamp_all_zero(series, name, thresholds.timestamp_all_zero))
    found.extend(_detect_fullwidth_mixed(series, name))
    found.extend(_detect_dup_concat(series, name))
    found.extend(_detect_numeric_in_text(series, name))
    found.extend(_detect_window_all_null(series, name, thresholds.null_run_factor))
    found.extend(_detect_high_missing(column, thresholds.high_missing))
    return found


def profile_column(frame: pd.DataFrame, name: str, top_n: int) -> ColumnProfile:
    """单列画像的公开入口，供 ``/validate/distribution`` 复用。"""
    return _profile_column(frame, name, top_n)


def _detect_row_duplicate(frame: pd.DataFrame) -> list[AnomalyPattern]:
    duplicated = int(frame.duplicated().sum())
    if duplicated == 0:
        return []
    return [AnomalyPattern(
        code="ROW_DUPLICATE",
        column="*",
        level=LEVEL_ROW,
        evidence=f"存在 {duplicated} 行与在先记录完全一致",
        affectedRows=duplicated,
        metric=round(duplicated / len(frame), 6) if len(frame) else 0.0,
    )]


def _detect_date_format_mixed(series: pd.Series, name: str) -> list[AnomalyPattern]:
    """同一列并存多种日期格式。只在文本列上检查：真正的 datetime 列不可能格式不
    一致。
    """
    text = _as_text(series)
    if text is None or text.empty:
        return []

    shapes = text.map(_date_shape)
    hits = shapes.dropna()
    # 至少八成取值像日期才认为是日期列，阈值偏高，宁可漏报也不误判编码列
    if len(hits) < 0.8 * len(text):
        return []
    counts = hits.value_counts()
    if len(counts) < 2:
        return []

    minority = int(counts.iloc[1:].sum())
    return [AnomalyPattern(
        code="DATE_FORMAT_MIXED",
        column=name,
        level=LEVEL_ROW,
        evidence=f"并存 {len(counts)} 种日期格式（{'/'.join(counts.index[:4])}），"
                 f"主格式 {counts.index[0]} 占 {counts.iloc[0] / len(hits):.2%}",
        affectedRows=minority,
        metric=round(minority / len(text), 6),
    )]


def _date_shape(value: str) -> str | None:
    for shape, pattern in _DATE_PATTERNS.items():
        if pattern.match(value):
            return shape
    return None


def _detect_timestamp_all_zero(series: pd.Series, name: str, threshold: float) -> list[AnomalyPattern]:
    """时间部分恒为 00:00:00。单看任何一行都正常，只有对整列做占比统计才会发现
    大量记录精确落在午夜（意味着时分秒字段在上游丢了）。
    """
    parsed = _as_datetime(series)
    if parsed is None:
        return []
    non_null = parsed.dropna()
    if len(non_null) == 0:
        return []

    midnight = non_null.dt.normalize() == non_null
    ratio = float(midnight.mean())
    if ratio <= threshold:
        return []
    return [AnomalyPattern(
        code="TIMESTAMP_ALL_ZERO",
        column=name,
        level=LEVEL_DISTRIBUTION,
        evidence=f"时间部分为 00:00:00 的占比 {ratio:.2%}，超过阈值 {threshold:.0%}；"
                 f"单行看不出问题，只有分布统计能发现",
        affectedRows=int(midnight.sum()),
        metric=round(ratio, 6),
    )]


def _detect_fullwidth_mixed(series: pd.Series, name: str) -> list[AnomalyPattern]:
    """全角/半角混用导致同一语义被拆成多个取值。判据是归一化之后取值数量减少了。"""
    text = _as_text(series)
    if text is None or text.empty:
        return []
    values = text.unique()
    if len(values) > 500:
        return []

    normalized = {value: unicodedata.normalize("NFKC", value) for value in values}
    if len(set(normalized.values())) == len(values):
        return []

    # 只统计归一化后与别的取值撞车的取值所影响的行数
    collided = {
        value for value in values
        if sum(1 for other in values if normalized[other] == normalized[value]) > 1
        and value != normalized[value]
    }
    affected = int(text.isin(collided).sum())
    merged = len(values) - len(set(normalized.values()))
    return [AnomalyPattern(
        code="FULLWIDTH_MIXED",
        column=name,
        level=LEVEL_DISTRIBUTION,
        evidence=f"全半角归一化后取值数从 {len(values)} 降到 {len(values) - merged}，"
                 f"说明 {merged} 组取值实为同一语义（例：{sorted(collided)[:2]}）",
        affectedRows=affected,
        metric=round(affected / len(text), 6),
    )]


def _detect_dup_concat(series: pd.Series, name: str) -> list[AnomalyPattern]:
    """取值被自身重复拼接 2–4 次（如 ``鼻导管吸氧鼻导管吸氧``）。纯字符串结构判断：
    某个取值恰好等于另一个已存在取值的整数倍重复。
    """
    text = _as_text(series)
    if text is None or text.empty:
        return []
    values = set(text.unique())
    if len(values) > 500:
        return []

    repeated: dict[str, str] = {}
    for value in values:
        for times in (2, 3, 4):
            if len(value) % times:
                continue
            unit = value[: len(value) // times]
            if unit * times == value and unit in values:
                repeated[value] = unit
                break
    if not repeated:
        return []

    affected = int(text.isin(repeated.keys()).sum())
    sample = next(iter(repeated))
    return [AnomalyPattern(
        code="DUP_CONCAT",
        column=name,
        level=LEVEL_DISTRIBUTION,
        evidence=f"{len(repeated)} 个取值是既有取值的整数倍重复拼接"
                 f"（例：{sample} = {repeated[sample]} × N）",
        affectedRows=affected,
        metric=round(affected / len(text), 6),
    )]


def _detect_numeric_in_text(series: pd.Series, name: str) -> list[AnomalyPattern]:
    """文本列里混入纯数字编码。判据是少数派：只有绝大多数是文本、少量是数字，才是
    上游系统直接存了编码值。
    """
    text = _as_text(series)
    if text is None or text.empty:
        return []
    numeric_mask = text.map(lambda value: bool(_PURE_NUMBER.match(value)))
    ratio = float(numeric_mask.mean())
    if ratio <= 0 or ratio >= 0.5:
        return []

    samples = sorted(text[numeric_mask].unique())[:5]
    return [AnomalyPattern(
        code="NUMERIC_IN_TEXT",
        column=name,
        level=LEVEL_DISTRIBUTION,
        evidence=f"文本列中 {ratio:.2%} 的取值是纯数字（{'/'.join(samples)}），"
                 f"疑似上游系统直接存了编码值",
        affectedRows=int(numeric_mask.sum()),
        metric=round(ratio, 6),
    )]


def _detect_window_all_null(series: pd.Series, name: str, factor: int) -> list[AnomalyPattern]:
    """某列存在异常长的连续空白段。全部检测器里唯一的统计推断，两个判据：

    一、显著长于随机缺失的期望。独立随机缺失下（缺失率 p、行数 n）最长连续空白段
    的期望长度约 ``log(n(1-p)) / log(1/p)``。用期望值而非固定行数，因为各列缺失率
    相差十倍。

    二、显著长于该列第二长的空白段。入院级属性（如 ``结局时间``）的空白天然成段
    出现，要求 ``最长段 > factor × 第二长段`` 把这类列排除掉。
    """
    total = len(series)
    if total < 100:
        return []
    is_null = series.isna().to_numpy()
    missing_rate = float(is_null.mean())
    if missing_rate <= 0 or missing_rate >= 1:
        return []

    runs = _true_run_lengths(is_null)
    if runs.size == 0:
        return []
    longest = int(runs[0])
    runner_up = int(runs[1]) if runs.size > 1 else 0

    expected = np.log(max(total * (1 - missing_rate), 2.0)) / np.log(1.0 / missing_rate)
    if longest <= max(20.0, factor * expected):
        return []
    if longest <= factor * runner_up:
        # 空白本来就成段出现，是结构而非缺陷
        return []

    return [AnomalyPattern(
        code="WINDOW_ALL_NULL",
        column=name,
        level=LEVEL_STRUCTURE,
        evidence=f"存在长度 {longest} 的连续空白段：按缺失率 {missing_rate:.2%} 推算，"
                 f"随机缺失下最长段期望仅约 {expected:.0f} 行；"
                 f"该列第二长的空白段只有 {runner_up} 行，说明这一段是孤立的结构性缺口，"
                 f"不是「本来就成段缺失」",
        affectedRows=longest,
        metric=round(longest / max(expected, 1.0), 4),
    )]


def _true_run_lengths(mask: np.ndarray) -> np.ndarray:
    """所有连续 True 段的长度，降序。"""
    if not mask.any():
        return np.array([], dtype=np.int64)
    # 两端补 False，用差分定位每段的起止
    padded = np.concatenate(([False], mask, [False]))
    edges = np.flatnonzero(padded[1:] != padded[:-1])
    lengths = edges[1::2] - edges[::2]
    return np.sort(lengths)[::-1]


def _detect_high_missing(column: ColumnProfile, threshold: float) -> list[AnomalyPattern]:
    """极高缺失率。code 是 ``HIGH_MISSING_RATE`` 而非 ``MISSING_SEMANTIC``：Python 只
    能确定「这列大部分是空」这个事实，缺失语义（隐含阴性 / 真实缺失）是临床判断，
    由 Java 侧结合知识库决定是否澄清。
    """
    if column.missingRate < threshold:
        return []
    return [AnomalyPattern(
        code="HIGH_MISSING_RATE",
        column=column.name,
        level=LEVEL_CLARIFY,
        evidence=f"缺失率 {column.missingRate:.2%}，超过阈值 {threshold:.0%}；"
                 f"缺失语义（隐含阴性 / 真实缺失）需要临床判断，不在此处推断",
        affectedRows=0,
        metric=round(column.missingRate, 6),
    )]


# ── 主键候选 ──

def _detect_key_candidates(frame: pd.DataFrame) -> list[KeyCandidate]:
    """找出能（近似）唯一标识一行的列或列组合，用于「按住院去重」这类任务。

    门槛是 0.95 而非 1.0：真实临床数据里几乎不存在完美主键，返回 ``uniqueRatio=0.988``
    这样的信息告诉医生「按这两列去重会丢掉 1.2% 的记录」，是一个需要他判断的取舍。
    """
    total = len(frame)
    if total == 0:
        return []

    exact: list[KeyCandidate] = []
    singles = []
    for name in frame.columns:
        ratio = frame[name].nunique(dropna=False) / total
        if ratio >= 1.0:
            exact.append(KeyCandidate(columns=[str(name)], uniqueRatio=1.0))
        elif ratio >= 0.05:
            # 只有区分度还行的列才值得进两列组合
            singles.append(name)

    if exact:
        return exact

    candidates: list[KeyCandidate] = []
    for combo in combinations(singles[:_MAX_SINGLE_COLUMNS], _MAX_KEY_COMBINATION):
        ratio = len(frame[list(combo)].drop_duplicates()) / total
        if ratio >= _KEY_MIN_UNIQUE_RATIO:
            candidates.append(KeyCandidate(
                columns=[str(name) for name in combo],
                uniqueRatio=round(ratio, 6),
            ))
    candidates.sort(key=lambda item: item.uniqueRatio, reverse=True)
    return candidates[:5]


# ── 类型辅助 ──

def _as_text(series: pd.Series) -> pd.Series | None:
    """取非空的字符串视图；不是文本列则返回 None。"""
    if pd.api.types.is_numeric_dtype(series) or pd.api.types.is_datetime64_any_dtype(series):
        return None
    values = series.dropna()
    if values.empty:
        return None
    return values.astype(str)


def _as_datetime(series: pd.Series) -> pd.Series | None:
    """把时间列（真 datetime 或字符串形式）统一成 datetime。字符串形式也要处理：
    日期格式混杂缺陷会把整列转成 object。
    """
    if pd.api.types.is_datetime64_any_dtype(series):
        return series
    text = _as_text(series)
    if text is None:
        return None
    shapes = text.map(_date_shape)
    if shapes.notna().mean() < 0.8:
        return None
    # 混合格式下不能指定单一 format，交给 pandas 逐值推断
    return pd.to_datetime(series, errors="coerce", format="mixed")
