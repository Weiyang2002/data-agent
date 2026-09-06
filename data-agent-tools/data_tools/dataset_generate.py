"""评测数据集生成：合成基线数据 + 缺陷注入 + golden 产出。

用合成数据：原始患者数据不得出内网，且真实数据没有 golden。同一个 seed 必须产出
完全相同的数据与 golden，是指标归因的前提。
"""

from __future__ import annotations

import json
import logging
from pathlib import Path

import numpy as np
import pandas as pd

from data_tools import columns as C
from data_tools import defects as D
from data_tools.config import config_value
from data_tools.schemas.dataset import (
    DatasetBaseline,
    DatasetGenerateRequest,
    DatasetGenerateResponse,
    InjectedDefect,
)

logger = logging.getLogger(__name__)

# golden 里每类缺陷最多记录多少行号（全量记录会让 golden.json 膨胀到几十 MB）
MAX_ROW_IDS = 2000

# 真实数据的人均入院次数
ADMISSIONS_PER_PATIENT = 1.36


def generate_dataset(request: DatasetGenerateRequest) -> DatasetGenerateResponse:
    # 先校验再生成：拼错的缺陷码会静默产出「少了一类缺陷」的评测集，必须在入口炸掉
    unknown = [spec.code for spec in request.defects if spec.code not in D.INJECTORS]
    if unknown:
        raise ValueError(f"不支持的缺陷类型: {', '.join(unknown)}；"
                         f"可用类型见 GET /dataset/defects")

    rng = np.random.default_rng(request.seed)

    df = _build_baseline_frame(request, rng)
    baseline = _compute_baseline(df)

    injected: list[InjectedDefect] = []
    requested = {spec.code: spec for spec in request.defects}

    # 按固定顺序注入，保证同 seed 可复现
    for code in D.APPLICATION_ORDER:
        spec = requested.get(code)
        if spec is None or spec.ratio <= 0:
            continue

        cols = D.resolve_columns(code, spec.columns)
        df, affected_idx = D.INJECTORS[code](df, rng, spec.ratio, cols)
        meta = D.DEFECT_META[code]

        row_ids = [int(i) for i in affected_idx]
        truncated = len(row_ids) > MAX_ROW_IDS
        injected.append(InjectedDefect(
            code=code,
            expectLevel=meta.level,
            affectedRows=len(affected_idx),
            rowIds=row_ids[:MAX_ROW_IDS],
            rowIdsTruncated=truncated,
            columns=cols,
            expectSignal=meta.expect_signal,
            userAsked=meta.user_asked,
        ))
        logger.info("注入缺陷 %s: 影响 %d 行, 列=%s", code, len(affected_idx), cols)

    expect_clarifications = [
        D.DEFECT_META[item.code].clarification
        for item in injected
        if D.DEFECT_META[item.code].clarification
    ]
    expect_no_clarifications = _build_expect_no_clarifications(injected)

    dataset_path, golden_path = _resolve_output_paths(request)
    df.to_parquet(dataset_path, index=False)

    response = DatasetGenerateResponse(
        datasetPath=str(dataset_path),
        goldenPath=str(golden_path),
        rowCount=len(df),
        seed=request.seed,
        baseline=baseline,
        injected=injected,
        expectClarifications=expect_clarifications,
        expectNoClarifications=expect_no_clarifications,
    )
    golden_path.write_text(
        json.dumps(response.model_dump(), ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    logger.info("数据集生成完成 rows=%d path=%s", len(df), dataset_path)
    return response


# ── 基线数据 ──

def _build_baseline_frame(request: DatasetGenerateRequest, rng: np.random.Generator) -> pd.DataFrame:
    """生成未注入任何缺陷的基线数据。仍带有真实数据的固有特征（高缺失率、文本列
    取值分布、人均入院次数），这些不是缺陷。
    """
    patient_count = request.patients
    admission_count = max(patient_count, int(round(patient_count * ADMISSIONS_PER_PATIENT)))

    # 先保证每个患者至少一次入院，剩余入院随机分配给已有患者
    owners = np.arange(patient_count)
    extra = admission_count - patient_count
    if extra > 0:
        owners = np.concatenate([owners, rng.choice(patient_count, size=extra, replace=True)])
    rng.shuffle(owners)

    admission_ids = np.array([f"A{i + 1:07d}" for i in range(admission_count)])
    patient_ids = np.array([f"P{owner + 1:06d}" for owner in owners])

    # 每次入院分摊若干条记录
    rows = request.rows
    per_admission = rng.multinomial(rows, np.ones(admission_count) / admission_count)
    admission_index = np.repeat(np.arange(admission_count), per_admission)

    frame = pd.DataFrame({
        C.COL_PATIENT: patient_ids[admission_index],
        C.COL_ADMISSION: admission_ids[admission_index],
    })

    # 入院时间：过去两年内随机
    admit_base = pd.Timestamp("2024-01-01")
    admit_offsets = rng.integers(0, 730 * 24, size=admission_count)
    admit_times = admit_base + pd.to_timedelta(admit_offsets, unit="h")
    frame[C.COL_ADMIT_TIME] = admit_times[admission_index]

    # 记录时间：入院后 0–20 天内
    record_offsets = rng.integers(0, 480, size=rows)
    frame[C.COL_RECORD_TIME] = frame[C.COL_ADMIT_TIME] + pd.to_timedelta(record_offsets, unit="h")

    # 生理指标：正态分布 + 按列缺失率打洞
    for col in C.NUMERIC_COLUMNS:
        mean, std = C.NORMAL_DISTRIBUTION[col]
        low, high = C.VALIDITY_RANGE[col]
        values = np.clip(rng.normal(mean, std, size=rows), low, high).round(1)
        frame[col] = _apply_missing(values, C.MISSING_RATE[col], rng)

    # 文本列
    frame[C.COL_CONSCIOUS] = _apply_missing(
        rng.choice(C.CONSCIOUS_VALUES, size=rows, p=_normalize(C.CONSCIOUS_WEIGHTS)),
        C.MISSING_RATE[C.COL_CONSCIOUS], rng)
    frame[C.COL_OXYGEN] = _apply_missing(
        rng.choice(C.OXYGEN_VALUES, size=rows, p=_normalize(C.OXYGEN_WEIGHTS)),
        C.MISSING_RATE[C.COL_OXYGEN], rng)
    frame[C.COL_VASOPRESSOR] = _apply_missing(
        rng.choice(C.VASOPRESSOR_VALUES, size=rows, p=_normalize(C.VASOPRESSOR_WEIGHTS)),
        C.MISSING_RATE[C.COL_VASOPRESSOR], rng)

    # 结局：按入院粒度决定，多结局取时间最早者（这里每次入院最多一个结局）
    outcome_per_admission = rng.choice(
        C.OUTCOME_VALUES, size=admission_count, p=_normalize(C.OUTCOME_WEIGHTS))
    frame[C.COL_OUTCOME] = outcome_per_admission[admission_index]
    outcome_offsets = rng.integers(24, 480, size=admission_count)
    outcome_times = admit_times + pd.to_timedelta(outcome_offsets, unit="h")
    outcome_times = np.where(outcome_per_admission == "无", np.datetime64("NaT"), outcome_times)
    frame[C.COL_OUTCOME_TIME] = pd.to_datetime(pd.Series(outcome_times[admission_index]))

    return frame[C.ALL_COLUMNS].reset_index(drop=True)


def _normalize(weights: list[float]) -> np.ndarray:
    array = np.asarray(weights, dtype=float)
    return array / array.sum()


def _apply_missing(values: np.ndarray, rate: float, rng: np.random.Generator) -> pd.Series:
    series = pd.Series(values, dtype="object" if values.dtype.kind in "UO" else "float64")
    if rate <= 0:
        return series
    mask = rng.random(len(series)) < rate
    series[mask] = np.nan
    return series


def _compute_baseline(df: pd.DataFrame) -> DatasetBaseline:
    """注入前的真实统计量，常识级校验拿处理后的数字跟这组基准比。"""
    patient_count = int(df[C.COL_PATIENT].nunique())
    admission_count = int(df[C.COL_ADMISSION].nunique())
    ratio = round(admission_count / patient_count, 4) if patient_count else 0.0
    return DatasetBaseline(
        patientCount=patient_count,
        admissionCount=admission_count,
        admissionsPerPatient=ratio,
    )


def _build_expect_no_clarifications(injected: list[InjectedDefect]) -> list[str]:
    """构造「不该问」的清单，澄清恰当率双向计分的依据。知识库里有明确依据的缺陷
    （越界阈值、重复行、格式归一）系统应自动决定，不应打扰用户。
    """
    catalog = {
        "OUT_OF_RANGE": "体温/收缩压越界如何处理（知识库 VALIDITY 规则已明确，应自动置空）",
        "ROW_DUPLICATE": "整行重复是否去重（无歧义，应自动去重）",
        "DATE_FORMAT_MIXED": "日期格式如何归一（无歧义，应自动统一为 ISO）",
        "FULLWIDTH_MIXED": "全半角是否统一（无歧义，应自动归一）",
        "DUP_CONCAT": "重复拼接的取值是否还原（无歧义，应自动还原）",
    }
    codes = {item.code for item in injected}
    return [text for code, text in catalog.items() if code in codes]


def _resolve_output_paths(request: DatasetGenerateRequest) -> tuple[Path, Path]:
    output_root = Path(config_value("dataTools.dataset.outputRoot", "DATA_AGENT_EVAL_ROOT", "./.eval"))
    output_root.mkdir(parents=True, exist_ok=True)
    name = request.datasetName or f"ds_seed{request.seed}_r{request.rows}"
    return output_root / f"{name}.parquet", output_root / f"{name}.golden.json"
