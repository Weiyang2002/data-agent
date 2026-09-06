"""数据画像的请求/响应模型，隐私约束的执行点。

响应体的形状本身就是约束：没有任何字段能装下原始数据行。唯一带出具体取值的是
``textValues``，且只对低基数列枚举（distinct 数超过 textValueTopN 的列不枚举），
所以标识列不会出现在返回体里。
"""

from pydantic import BaseModel, Field


class ProfileRequest(BaseModel):
    datasetPath: str = Field(description="parquet 文件路径")
    textValueTopN: int = Field(
        default=50, ge=1, le=500,
        description="文本列取值枚举上限，distinct 数超过它的列不枚举取值",
    )
    sampleRows: int = Field(
        default=0, ge=0, le=100,
        description="采样行数，默认 0 即不返回任何原始行",
    )


class NumericProfile(BaseModel):
    min: float | None = None
    max: float | None = None
    mean: float | None = None
    p50: float | None = None
    p95: float | None = None
    zeroRatio: float | None = Field(default=None, description="取值为 0 的占比")


class TextValue(BaseModel):
    value: str
    count: int
    ratio: float


class ColumnProfile(BaseModel):
    name: str
    dtype: str
    missingRate: float
    distinctCount: int
    numeric: NumericProfile | None = None
    textValues: list[TextValue] | None = Field(
        default=None,
        description="仅低基数列有值；高基数列为 None，不枚举",
    )
    textValuesTruncated: bool = Field(
        default=False,
        description="True 表示该列基数超限、取值未枚举（区别于「没取值」）",
    )


class AnomalyPattern(BaseModel):
    """确定性规则检出的异常模式。

    ``code`` 与 ``defects.py`` 的缺陷码共用命名空间，评测可直接比对。不含
    OUT_OF_RANGE（越界判定需要知识库阈值，属 ``/validate/row``）和
    COLUMN_MISALIGN / TIME_INVERSION（常识级，非统计可检出）。
    """

    code: str
    column: str
    level: str = Field(description="ROW / DISTRIBUTION / STRUCTURE / CLARIFY")
    evidence: str = Field(description="人类可读的证据，写进澄清问题和报告")
    affectedRows: int
    metric: float | None = Field(default=None, description="触发该判定的统计量")


class KeyCandidate(BaseModel):
    columns: list[str]
    uniqueRatio: float


class ProfileResponse(BaseModel):
    datasetPath: str
    rowCount: int
    columnCount: int
    columns: list[ColumnProfile]
    anomalyPatterns: list[AnomalyPattern]
    keyCandidates: list[KeyCandidate]
    profileMillis: int
