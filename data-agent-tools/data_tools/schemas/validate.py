"""三层校验中前两层（行级、分布级）的请求/响应模型。

第三层常识级在 Java 侧 ``CommonSenseValidator``，输入是处理前后的画像 diff。
"""

from pydantic import BaseModel, Field


# ── 行级 ──

class ValidityRule(BaseModel):
    """一条行级校验规则。阈值从请求里传进来，Python 侧没有任何默认值：Java 先查
    知识库拿到 {min, max} 连同 ruleId、来源文档一起发过来，Python 只负责比对。
    """

    ruleId: str = Field(description="知识库规则主键，回填到校验发现上做引用标注")
    column: str
    min: float | None = None
    max: float | None = None
    allowedValues: list[str] | None = Field(
        default=None, description="文本列的合法取值集合；与 min/max 互斥",
    )
    unit: str | None = None
    sourceDoc: str = Field(default="", description="来源文档名，原样回带")
    sourceLocator: str = Field(default="", description="文档内定位，原样回带")


class ValidateRowRequest(BaseModel):
    datasetPath: str
    rules: list[ValidityRule] = Field(
        description="至少一条。传空会被拒绝——见 validation.validate_row 的说明",
    )
    maxSamplesPerRule: int = Field(default=20, ge=0, le=200)


class RowFinding(BaseModel):
    ruleId: str
    column: str
    level: str = "ROW"
    violationCount: int
    violationRatio: float
    checkedCount: int = Field(description="参与比对的非空行数，缺失行不计入违规")
    sampleRowIds: list[int] = Field(default_factory=list)
    sampleValues: list[str] = Field(
        default_factory=list,
        description="违规样例值，供人工核对，不进 Prompt",
    )
    expectedMin: float | None = None
    expectedMax: float | None = None
    sourceDoc: str = ""
    sourceLocator: str = ""
    evidence: str = ""


class ValidateRowResponse(BaseModel):
    datasetPath: str
    totalRows: int
    checkedRules: int
    findings: list[RowFinding]


# ── 分布级 ──

class DistributionThresholds(BaseModel):
    """分布级阈值。与行级不同，允许有 Python 侧默认值：它是统计工程判断
    （「00:00:00 占一半以上就该告警」），没有可引用的院内规范。
    """

    timestampAllZeroRatio: float | None = Field(default=None, ge=0.0, le=1.0)
    highMissingRate: float | None = Field(default=None, ge=0.0, le=1.0)
    nullRunFactor: int | None = Field(default=None, ge=2)


class ValidateDistributionRequest(BaseModel):
    datasetPath: str
    columns: list[str] | None = Field(
        default=None, description="限定检查列；缺省检查全部列",
    )
    thresholds: DistributionThresholds = Field(default_factory=DistributionThresholds)
    textValueTopN: int = Field(default=50, ge=1, le=500)


class ValidateDistributionResponse(BaseModel):
    datasetPath: str
    totalRows: int
    findings: list[dict] = Field(
        description="结构同 ProfileResponse.anomalyPatterns，复用同一批检测器",
    )
    columnStats: list[dict] = Field(
        default_factory=list,
        description="被检查列的统计摘要，供 Java 侧构造处理前后 diff",
    )
