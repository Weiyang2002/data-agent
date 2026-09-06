"""沙箱执行的请求/响应模型。"""

from typing import Any

from pydantic import BaseModel, Field


class ExecuteRequest(BaseModel):
    taskId: str = Field(description="任务号，同时作为沙箱工作目录名")
    code: str = Field(description="待执行的 Python 代码，必须定义 clean(df, params) 函数")
    params: dict[str, Any] = Field(
        default_factory=dict,
        description="临床参数注入通道（阈值、取值映射等）。Java 从知识库查出后经此传入，"
                   "运行时注入到 clean 的第二个参数，不出现在代码生成 Prompt 里。"
                   "非空时强制要求代码引用 params",
    )
    inputPath: str = Field(description="输入 parquet 路径，由沙箱宿主读取")
    outputPath: str | None = Field(
        default=None,
        description="输出 parquet 路径；缺省时写到沙箱工作目录下",
    )
    timeoutSeconds: int = Field(default=120, gt=0, le=1800)
    memoryLimitMb: int = Field(default=2048, gt=0)
    dryRun: bool = Field(
        default=False,
        description="只做静态检查、不执行",
    )


class ExecuteResponse(BaseModel):
    success: bool
    exitCode: int | None = None
    stdout: str = ""
    stderr: str = ""
    durationMs: int = 0

    blockedReason: str | None = Field(
        default=None,
        description="静态检查拦截原因；非空表示代码根本没被执行",
    )
    blockedDetail: list[str] = Field(
        default_factory=list,
        description="全部违规项，一次性返回，便于自修复一轮改完",
    )

    outputPath: str | None = None
    outputRowCount: int | None = None
    outputColumnCount: int | None = None

    timedOut: bool = False
    memoryLimitEnforced: bool = Field(
        default=False,
        description="内存上限是否真的生效。Windows 上 resource.setrlimit 不可用，返回 False",
    )
