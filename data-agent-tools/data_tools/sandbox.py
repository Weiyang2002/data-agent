"""沙箱宿主：静态检查 → 子进程执行 → 超时兜底。

三道防线：静态检查（``static_check.py``）、进程隔离（本模块 + ``sandbox_runner.py``，
独立子进程 + isolated 模式 + 受限 builtins + 独立工作目录 + POSIX 内存/CPU 限额）、
超时兜底（本模块）。Windows 上 ``resource`` 模块不存在，内存上限拿不到，响应体的
``memoryLimitEnforced`` 会如实返回 False。

用子进程而非 ``exec`` 在本进程跑：死循环可中断、内存耗尽不拖垮 FastAPI 进程、
不共享 ``sys.modules``。代价是每次启动 ~200ms。
"""

from __future__ import annotations

import json
import logging
import subprocess
import sys
import time
from pathlib import Path

from data_tools.config import config_value
from data_tools.sandbox_runner import RESULT_MARKER
from data_tools.schemas.execute import ExecuteRequest, ExecuteResponse
from data_tools.static_check import check_code

logger = logging.getLogger(__name__)

_RUNNER = Path(__file__).with_name("sandbox_runner.py")

# 回传给 Java 的 stdout/stderr 上限。
# 不截断的后果：一个死循环里的 print 能产出几百 MB 文本，
# 而这些文本最终会被回喂给模型做自修复——直接把上下文撑爆。
_MAX_STREAM_CHARS = 8000


def execute_code(request: ExecuteRequest) -> ExecuteResponse:
    started = time.perf_counter()

    # 第 1 道：静态检查。带了临床参数就强制要求代码引用 params
    check = check_code(request.code, require_params=bool(request.params))
    if not check.passed:
        logger.warning("沙箱静态检查拦截 taskId=%s 违规=%d 项",
                       request.taskId, len(check.violations))
        return ExecuteResponse(
            success=False,
            blockedReason=check.reason,
            blockedDetail=check.violations,
            durationMs=int((time.perf_counter() - started) * 1000),
        )

    if request.dryRun:
        return ExecuteResponse(
            success=True,
            blockedReason=None,
            durationMs=int((time.perf_counter() - started) * 1000),
        )

    work_dir = _prepare_work_dir(request.taskId)
    code_path = work_dir / "clean_script.py"
    code_path.write_text(request.code, encoding="utf-8")

    output_path = Path(request.outputPath) if request.outputPath \
        else work_dir / "output.parquet"
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path = output_path.resolve()

    payload = json.dumps({
        "codePath": str(code_path),
        "inputPath": str(Path(request.inputPath).resolve()),
        "outputPath": str(output_path),
        "memoryLimitMb": request.memoryLimitMb,
        "params": request.params,
        # ensure_ascii=True：见 sandbox_runner._force_utf8_streams
    }, ensure_ascii=True)

    # 第 2、3 道：子进程 + 超时
    # -I = isolated：忽略 PYTHONPATH / 用户 site-packages / 不把 cwd 加进 sys.path
    command = [sys.executable, "-I", str(_RUNNER)]
    timed_out = False
    try:
        completed = subprocess.run(
            command,
            input=payload,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=request.timeoutSeconds,
            cwd=str(work_dir),
        )
        stdout, stderr, exit_code = completed.stdout, completed.stderr, completed.returncode
    except subprocess.TimeoutExpired as expired:
        timed_out = True
        stdout = _decode(expired.stdout)
        stderr = _decode(expired.stderr) + \
            f"\n[sandbox] 执行超过 {request.timeoutSeconds}s，进程已被强制终止"
        exit_code = None

    duration = int((time.perf_counter() - started) * 1000)
    user_stdout, result = _split_result(stdout)

    if timed_out or result is None:
        # result 为 None：子进程没跑到写哨兵那一步（被 SIGKILL / OOM killer / 崩溃）
        return ExecuteResponse(
            success=False,
            exitCode=exit_code,
            stdout=_clip(user_stdout),
            stderr=_clip(stderr or "[sandbox] 子进程异常退出，无结果输出"),
            durationMs=duration,
            timedOut=timed_out,
            outputPath=str(output_path),
        )

    success = bool(result.get("ok"))
    if not success and result.get("error"):
        stderr = (stderr + "\n" + result["error"]).strip()

    if not result.get("memoryLimitEnforced"):
        # 显式喊出降级，不静默
        logger.warning("沙箱内存上限未生效（当前平台无 resource 模块），"
                       "taskId=%s 仅有超时兜底", request.taskId)

    return ExecuteResponse(
        success=success,
        exitCode=exit_code,
        stdout=_clip(user_stdout),
        stderr=_clip(stderr),
        durationMs=duration,
        outputPath=str(output_path) if success else None,
        outputRowCount=result.get("outputRowCount"),
        outputColumnCount=result.get("outputColumnCount"),
        memoryLimitEnforced=bool(result.get("memoryLimitEnforced")),
    )


def _prepare_work_dir(task_id: str) -> Path:
    root = Path(config_value("dataTools.sandbox.workRoot",
                             "DATA_AGENT_SANDBOX_ROOT", "./.sandbox"))
    # 任务号来自外部，必须过滤路径分隔符，否则 taskId="../../etc" 就能写出沙箱
    safe = "".join(ch for ch in task_id if ch.isalnum() or ch in "-_")[:64] or "task"
    work_dir = root / safe
    work_dir.mkdir(parents=True, exist_ok=True)
    # 必须返回绝对路径：子进程的 cwd 就是这个目录
    return work_dir.resolve()


def _split_result(stdout: str) -> tuple[str, dict | None]:
    """把业务代码的 print 输出和哨兵结果行分开。"""
    lines = stdout.splitlines()
    result = None
    kept: list[str] = []
    for line in lines:
        if line.startswith(RESULT_MARKER):
            try:
                result = json.loads(line[len(RESULT_MARKER):].strip())
            except json.JSONDecodeError:
                logger.warning("沙箱结果行解析失败: %s", line[:200])
            continue
        kept.append(line)
    return "\n".join(kept), result


def _decode(raw) -> str:
    if raw is None:
        return ""
    if isinstance(raw, bytes):
        return raw.decode("utf-8", errors="replace")
    return str(raw)


def _clip(text: str) -> str:
    if text is None:
        return ""
    if len(text) <= _MAX_STREAM_CHARS:
        return text
    return text[:_MAX_STREAM_CHARS] + f"\n...[已截断，原长度 {len(text)} 字符]"
