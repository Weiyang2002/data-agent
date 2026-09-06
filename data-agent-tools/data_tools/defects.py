"""缺陷注入器。缺陷分类学来自真实的临床数据清洗经历，每一类对应一次真实返工。

- 每类缺陷标注「应检出层级」（行级 / 分布级 / 常识级），分层检出率逐层下降。
- 每类缺陷标注 `userAsked`：`False` 表示用户不会提到但系统应主动报告（L4 发现类）。
- 注入顺序固定（见 `APPLICATION_ORDER`），否则同 seed 无法复现。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Callable

import numpy as np
import pandas as pd

from data_tools import columns as C


# ── 层级常量 ────────────────────────────────────────
LEVEL_ROW = "ROW"
LEVEL_DISTRIBUTION = "DISTRIBUTION"
LEVEL_COMMON_SENSE = "COMMON_SENSE"
LEVEL_STRUCTURE = "STRUCTURE"
LEVEL_CLARIFY = "CLARIFY"


@dataclass
class DefectMeta:
    level: str
    expect_signal: str
    default_columns: list[str] = field(default_factory=list)
    user_asked: bool = True
    clarification: str | None = None
    """该缺陷应触发的澄清点描述；None 表示不该触发澄清"""


DEFECT_META: dict[str, DefectMeta] = {
    "ROW_DUPLICATE": DefectMeta(
        level=LEVEL_ROW,
        expect_signal="存在整行完全一致的重复记录",
    ),
    "OUT_OF_RANGE": DefectMeta(
        level=LEVEL_ROW,
        expect_signal="生理指标超出知识库 VALIDITY 阈值",
        default_columns=[C.COL_TEMP, C.COL_SBP],
    ),
    "DATE_FORMAT_MIXED": DefectMeta(
        level=LEVEL_ROW,
        expect_signal="同一日期列并存多种格式（2026-08-09 / 2026/08/09 / 26/08/09）",
        default_columns=[C.COL_ADMIT_TIME],
    ),
    "TIMESTAMP_ALL_ZERO": DefectMeta(
        level=LEVEL_DISTRIBUTION,
        expect_signal="时间列的时间部分恒为 00:00:00，占比异常高",
        default_columns=[C.COL_ADMIT_TIME],
        user_asked=False,
    ),
    "FULLWIDTH_MIXED": DefectMeta(
        level=LEVEL_DISTRIBUTION,
        expect_signal="文本列全角与半角括号混用，导致同一语义被拆成多个取值",
        default_columns=[C.COL_OXYGEN],
        user_asked=False,
    ),
    "DUP_CONCAT": DefectMeta(
        level=LEVEL_DISTRIBUTION,
        expect_signal="文本取值被重复拼接 2–4 次",
        default_columns=[C.COL_OXYGEN],
        user_asked=False,
    ),
    "NUMERIC_IN_TEXT": DefectMeta(
        level=LEVEL_DISTRIBUTION,
        expect_signal="文本列中混入数字编码（如意识列出现 1 / 2）",
        default_columns=[C.COL_CONSCIOUS],
        user_asked=False,
    ),
    "COLUMN_MISALIGN": DefectMeta(
        level=LEVEL_COMMON_SENSE,
        expect_signal="人均入院次数 ≈ 1.00 —— 代码无报错、格式正常、无异常值，"
                      "但常识上不可能人人只住院一次",
        default_columns=[C.COL_PATIENT, C.COL_ADMISSION],
        user_asked=False,
    ),
    "TIME_INVERSION": DefectMeta(
        level=LEVEL_COMMON_SENSE,
        expect_signal="记录时间早于入院时间",
        default_columns=[C.COL_RECORD_TIME],
        user_asked=False,
    ),
    "WINDOW_ALL_NULL": DefectMeta(
        level=LEVEL_STRUCTURE,
        expect_signal="某段连续记录内某列完全空白",
        default_columns=[C.COL_SPO2],
        user_asked=False,
    ),
    "MISSING_SEMANTIC": DefectMeta(
        level=LEVEL_CLARIFY,
        expect_signal="升压药缺失率 99%+，语义是隐含阴性（应填 0）而非真实缺失（插值）",
        default_columns=[C.COL_VASOPRESSOR],
        clarification="升压药列的缺失语义：隐含阴性还是真实缺失",
    ),
}

# 注入顺序固定：结构性改动在前 → 值改动 → 类型改动（日期转字符串）→ 行数改动。
# ROW_DUPLICATE 必须最后且把副本追加到末尾，避免先前记录的位置索引失效。
APPLICATION_ORDER = [
    "MISSING_SEMANTIC",
    "COLUMN_MISALIGN",
    "TIME_INVERSION",
    "WINDOW_ALL_NULL",
    "OUT_OF_RANGE",
    "FULLWIDTH_MIXED",
    "DUP_CONCAT",
    "NUMERIC_IN_TEXT",
    "TIMESTAMP_ALL_ZERO",
    "DATE_FORMAT_MIXED",
    "ROW_DUPLICATE",
]


def resolve_columns(code: str, requested: list[str] | None) -> list[str]:
    if requested:
        return requested
    return list(DEFECT_META[code].default_columns)


def _pick_rows(rng: np.random.Generator, total: int, ratio: float) -> np.ndarray:
    """按比例随机选行。ratio<=0 返回空。"""
    count = int(round(total * ratio))
    count = max(0, min(total, count))
    if count == 0:
        return np.array([], dtype=np.int64)
    return np.sort(rng.choice(total, size=count, replace=False))


# ── 各缺陷的注入实现，统一签名 (df, rng, ratio, cols) -> (df, 受影响位置索引) ──


def _inject_missing_semantic(df, rng, ratio, cols):
    """升压药列制造精确的极高缺失率。同样是空值，升压药高缺失是隐含阴性（该填 0），
    体温缺失是真实缺失（该插值），处理方式相反，需要领域知识。

    做法：先全部置空，再给恰好 (1-ratio) 的行赋上真实取值，缺失率才精确可控
    （只置空未被选中的行会因基线已有高缺失而把缺失率推到 100%）。
    """
    total = len(df)
    for col in cols:
        # 显式建成 object 列：直接赋 np.nan 会让 pandas 推断成 float64，
        # 之后往里写文本取值会触发 dtype 不兼容告警（未来版本将直接报错）。
        df[col] = pd.Series([np.nan] * total, index=df.index, dtype="object")
        keep = _pick_rows(rng, total, max(0.0, 1.0 - ratio))
        if len(keep) == 0:
            continue
        values = rng.choice(
            C.VASOPRESSOR_VALUES,
            size=len(keep),
            p=np.asarray(C.VASOPRESSOR_WEIGHTS) / np.sum(C.VASOPRESSOR_WEIGHTS),
        )
        df.loc[df.index[keep], col] = values
    return df, np.arange(total)


def _inject_column_misalign(df, rng, ratio, cols):
    """患者列与入院时间列错位。让每次入院都对应一个唯一患者，使人均入院次数塌成
    1.00（代码不报错、格式正常、无异常值，只有常识能发现）。
    """
    if not cols:
        return df, np.array([], dtype=np.int64)
    patient_col = cols[0]
    admission_col = cols[1] if len(cols) > 1 else C.COL_ADMISSION
    df[patient_col] = df[admission_col].astype(str).str.replace("A", "P", regex=False)
    return df, np.arange(len(df))


def _inject_time_inversion(df, rng, ratio, cols):
    """记录时间早于入院时间 —— 上游多系统聚合时的时序错误。"""
    idx = _pick_rows(rng, len(df), ratio)
    if len(idx) == 0:
        return df, idx
    for col in cols:
        offsets = pd.to_timedelta(rng.integers(1, 72, size=len(idx)), unit="h")
        df.loc[df.index[idx], col] = df.loc[df.index[idx], C.COL_ADMIT_TIME].values - offsets
    return df, idx


def _inject_window_all_null(df, rng, ratio, cols):
    """连续一段记录里某列完全空白 —— 窗口切分时才会暴露的结构问题。"""
    total = len(df)
    span = max(1, int(total * ratio))
    if span >= total:
        start = 0
        span = total
    else:
        start = int(rng.integers(0, total - span))
    idx = np.arange(start, start + span)
    for col in cols:
        df.loc[df.index[idx], col] = np.nan
    return df, idx


def _inject_out_of_range(df, rng, ratio, cols):
    """生理值越界 —— 应由行级校验用知识库阈值检出。"""
    idx = _pick_rows(rng, len(df), ratio)
    if len(idx) == 0:
        return df, idx
    for col in cols:
        low, high = C.VALIDITY_RANGE.get(col, (0.0, 100.0))
        # 一半打到下界之下，一半打到上界之上
        half = len(idx) // 2
        below = low - rng.uniform(1.0, 20.0, size=half)
        above = high + rng.uniform(1.0, 50.0, size=len(idx) - half)
        df.loc[df.index[idx], col] = np.concatenate([below, above])
    return df, idx


def _inject_fullwidth_mixed(df, rng, ratio, cols):
    """全角半角括号混用 —— 同一语义被拆成两个取值，破坏取值枚举。"""
    idx = _pick_rows(rng, len(df), ratio)
    if len(idx) == 0:
        return df, idx
    for col in cols:
        values = df.loc[df.index[idx], col].astype("object")
        converted = values.map(
            lambda v: v.replace("(", "（").replace(")", "）") if isinstance(v, str) else v
        )
        df.loc[df.index[idx], col] = converted
    return df, idx


def _inject_dup_concat(df, rng, ratio, cols):
    """文本取值被重复拼接 2–4 次 —— 多系统字段合并时的真实产物。"""
    idx = _pick_rows(rng, len(df), ratio)
    if len(idx) == 0:
        return df, idx
    times = rng.integers(2, 5, size=len(idx))
    for col in cols:
        values = df.loc[df.index[idx], col].astype("object").tolist()
        repeated = [
            v * int(t) if isinstance(v, str) else v
            for v, t in zip(values, times)
        ]
        df.loc[df.index[idx], col] = repeated
    return df, idx


def _inject_numeric_in_text(df, rng, ratio, cols):
    """文本列混入数字编码 —— 部分上游系统直接存了编码值。"""
    idx = _pick_rows(rng, len(df), ratio)
    if len(idx) == 0:
        return df, idx
    codes = rng.integers(1, 3, size=len(idx)).astype(str)
    for col in cols:
        df.loc[df.index[idx], col] = codes
    return df, idx


def _inject_timestamp_all_zero(df, rng, ratio, cols):
    """时间部分恒为 00:00:00。分布级信号：单看任何一行都正常，只有统计占比能发现。"""
    idx = _pick_rows(rng, len(df), ratio)
    if len(idx) == 0:
        return df, idx
    for col in cols:
        df.loc[df.index[idx], col] = pd.to_datetime(
            df.loc[df.index[idx], col]
        ).dt.normalize()
    return df, idx


_DATE_FORMATS = ["%Y-%m-%d %H:%M:%S", "%Y/%m/%d %H:%M:%S", "%y/%m/%d", "%Y.%m.%d %H:%M"]


def _inject_date_format_mixed(df, rng, ratio, cols):
    """日期格式混杂 —— 多院区多套 HIS 聚合的必然产物。整列转成字符串（CSV 聚合的
    日期列本来就整列是文本，且混合 Timestamp 与 str 的 object 列无法写入 parquet），
    未选中的行用 ISO 格式，选中的行用另外三种格式之一。
    """
    idx = _pick_rows(rng, len(df), ratio)
    if len(idx) == 0:
        return df, idx
    selected = set(int(i) for i in idx)
    for col in cols:
        source = pd.to_datetime(df[col], errors="coerce")
        picks = rng.integers(1, len(_DATE_FORMATS), size=len(df))
        rendered = []
        for pos in range(len(df)):
            value = source.iloc[pos]
            if pd.isna(value):
                rendered.append(None)
                continue
            fmt = _DATE_FORMATS[picks[pos]] if pos in selected else _DATE_FORMATS[0]
            rendered.append(pd.Timestamp(value).strftime(fmt))
        df[col] = pd.Series(rendered, index=df.index, dtype="object")
    return df, idx


def _inject_row_duplicate(df, rng, ratio, cols):
    """整行重复。必须最后执行，且副本追加到末尾，保持先前缺陷记录的位置索引有效。"""
    total = len(df)
    idx = _pick_rows(rng, total, ratio)
    if len(idx) == 0:
        return df, idx
    duplicates = df.iloc[idx].copy()
    df = pd.concat([df, duplicates], ignore_index=True)
    # 返回副本的位置索引（原行本身不算缺陷，重复出现的那份才是）
    return df, np.arange(total, total + len(idx))


INJECTORS: dict[str, Callable] = {
    "MISSING_SEMANTIC": _inject_missing_semantic,
    "COLUMN_MISALIGN": _inject_column_misalign,
    "TIME_INVERSION": _inject_time_inversion,
    "WINDOW_ALL_NULL": _inject_window_all_null,
    "OUT_OF_RANGE": _inject_out_of_range,
    "FULLWIDTH_MIXED": _inject_fullwidth_mixed,
    "DUP_CONCAT": _inject_dup_concat,
    "NUMERIC_IN_TEXT": _inject_numeric_in_text,
    "TIMESTAMP_ALL_ZERO": _inject_timestamp_all_zero,
    "DATE_FORMAT_MIXED": _inject_date_format_mixed,
    "ROW_DUPLICATE": _inject_row_duplicate,
}

SUPPORTED_CODES = list(DEFECT_META.keys())
