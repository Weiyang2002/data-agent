"""FastAPI 入口。

端点：
| 端点                        | 职责 |
|---|---|
| GET  /health                | 存活探测 + 沙箱自检 |
| POST /dataset/generate      | 缺陷注入生成评测数据 |
| GET  /dataset/defects       | 可用缺陷类型 |
| GET  /eval/cases            | 评测用例目录 |
| POST /profile               | 数据画像（隐私约束的执行点） |
| POST /execute               | 沙箱执行 |
| POST /validate/row          | 行级校验，阈值由 Java 传入 |
| POST /validate/distribution | 分布级校验 |

所有端点都不做业务决策：回答「数据是什么样」「这段代码能不能跑」「这些值符不符合
传进来的规则」，不回答「该不该问医生」「这个结论算不算通过」。
"""

import logging
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request

from data_tools import SERVICE_NAME, SERVICE_VERSION
from data_tools.config import config_value
from data_tools.dataset_generate import generate_dataset
from data_tools.defects import DEFECT_META
from data_tools.eval_cases import level_distribution, list_cases, validate_catalog
from data_tools.profiling import profile_dataset
from data_tools.sandbox import execute_code
from data_tools.schemas.dataset import DatasetGenerateRequest, DatasetGenerateResponse
from data_tools.schemas.execute import ExecuteRequest, ExecuteResponse
from data_tools.schemas.health import HealthResponse
from data_tools.schemas.profile import ProfileRequest, ProfileResponse
from data_tools.schemas.validate import (
    ValidateDistributionRequest,
    ValidateDistributionResponse,
    ValidateRowRequest,
    ValidateRowResponse,
)
from data_tools.validation import validate_distribution, validate_row

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s - %(message)s",
)
logger = logging.getLogger(__name__)

app = FastAPI(title=SERVICE_NAME, version=SERVICE_VERSION)

TRACE_HEADER = "X-Trace-Id"

# 启动即校验评测用例目录：用例引用的缺陷码必须存在、caseId 不得重复
validate_catalog()


@app.middleware("http")
async def trace_middleware(request: Request, call_next):
    """把 Java 侧传来的 traceId 记进日志并原样返回，实现跨语言 trace 贯穿。"""
    trace_id = request.headers.get(TRACE_HEADER, "-")
    logger.info("收到请求 %s %s traceId=%s", request.method, request.url.path, trace_id)
    response = await call_next(request)
    response.headers[TRACE_HEADER] = trace_id
    return response


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    """存活探测 + 沙箱可用性自检。sandboxReady 实际检查沙箱工作目录能否创建。"""
    return HealthResponse(
        status="ok",
        service=SERVICE_NAME,
        version=SERVICE_VERSION,
        sandboxReady=_check_sandbox_ready(),
    )


@app.get("/dataset/defects")
def list_defects() -> dict[str, object]:
    """列出支持的缺陷类型及其应检出层级，供 Java 侧构造评测请求时做校验。"""
    return {
        "defects": [
            {
                "code": code,
                "expectLevel": meta.level,
                "defaultColumns": meta.default_columns,
                "userAsked": meta.user_asked,
                "expectSignal": meta.expect_signal,
            }
            for code, meta in DEFECT_META.items()
        ]
    }


@app.post("/dataset/generate", response_model=DatasetGenerateResponse)
def dataset_generate(request: DatasetGenerateRequest) -> DatasetGenerateResponse:
    """生成带 golden 的评测数据集，评测体系的地基。"""
    try:
        return generate_dataset(request)
    except ValueError as error:
        raise HTTPException(status_code=400, detail=str(error)) from error


@app.get("/eval/cases")
def eval_cases() -> dict[str, object]:
    """评测用例目录。定义在 Python 侧：用例引用缺陷码，而缺陷码的权威在 defects.py。"""
    return {
        "total": len(list_cases()),
        "levelDistribution": level_distribution(),
        "cases": list_cases(),
    }


@app.post("/profile", response_model=ProfileResponse)
def profile(request: ProfileRequest) -> ProfileResponse:
    """数据画像，隐私约束的执行点：返回体只含 schema 与统计量，不含任何原始行。
    ``anomalyPatterns`` 由确定性规则检出，LLM 只拿它写一段自然语言归纳。
    """
    try:
        return profile_dataset(request)
    except FileNotFoundError as error:
        raise HTTPException(status_code=404, detail=str(error)) from error
    except ValueError as error:
        raise HTTPException(status_code=400, detail=str(error)) from error


@app.post("/execute", response_model=ExecuteResponse)
def execute(request: ExecuteRequest) -> ExecuteResponse:
    """沙箱执行。静态检查拦截时返回 HTTP 200 + success=false + blockedReason（不是
    4xx），因为「代码被拦下来」是一次正常的业务结果，供 ExecutorAgent 做自修复。
    """
    return execute_code(request)


@app.post("/validate/row", response_model=ValidateRowResponse)
def validate_row_endpoint(request: ValidateRowRequest) -> ValidateRowResponse:
    """行级校验。阈值全部由 Java 从知识库查出后传入，Python 侧不含任何临床数字。"""
    try:
        return validate_row(request)
    except FileNotFoundError as error:
        raise HTTPException(status_code=404, detail=str(error)) from error
    except ValueError as error:
        raise HTTPException(status_code=400, detail=str(error)) from error


@app.post("/validate/distribution", response_model=ValidateDistributionResponse)
def validate_distribution_endpoint(
        request: ValidateDistributionRequest) -> ValidateDistributionResponse:
    """分布级校验。与 /profile 共用同一批检测器，处理前后可直接比对。"""
    try:
        return validate_distribution(request)
    except FileNotFoundError as error:
        raise HTTPException(status_code=404, detail=str(error)) from error
    except ValueError as error:
        raise HTTPException(status_code=400, detail=str(error)) from error


def _check_sandbox_ready() -> bool:
    work_root = config_value("dataTools.sandbox.workRoot", "DATA_AGENT_SANDBOX_ROOT", "./.sandbox")
    try:
        path = Path(work_root)
        path.mkdir(parents=True, exist_ok=True)
        probe = path / ".probe"
        probe.write_text("ok", encoding="utf-8")
        probe.unlink()
        return True
    except OSError as error:
        logger.warning("沙箱工作目录不可用 workRoot=%s, error=%s", work_root, error)
        return False
