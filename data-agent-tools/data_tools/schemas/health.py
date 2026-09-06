from pydantic import BaseModel


class HealthResponse(BaseModel):
    """与 Java 侧 ToolsHealthResponse 字段一一对应。

    注意 sandboxReady 用驼峰而不是 Python 惯用的 snake_case：
    跨语言接口不做字段名映射，两侧保持完全一致，
    避免出现「Java 传 sandboxReady、Python 收 sandbox_ready」这类
    只在运行时才暴露的静默错位。
    """

    status: str
    service: str
    version: str
    sandboxReady: bool
