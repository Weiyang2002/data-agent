"""评测数据集生成的请求/响应模型。

字段名与 Java 侧 toolsclient/model 下的 DTO 完全一致，不做映射。
"""

from pydantic import BaseModel, Field


class DefectSpec(BaseModel):
    """单个缺陷的注入配置。"""

    code: str = Field(description="缺陷类型码，见 defects.DefectCode")
    ratio: float = Field(default=0.0, ge=0.0, le=1.0, description="注入比例，按行占比")
    columns: list[str] | None = Field(default=None, description="作用列；为空则用该缺陷的默认列")


class DatasetGenerateRequest(BaseModel):
    rows: int = Field(default=50000, gt=0, le=2_000_000, description="生成行数")
    patients: int = Field(default=3000, gt=0, description="患者数")
    seed: int = Field(default=42, description="随机种子，同 seed 必须产出完全相同的数据")
    defects: list[DefectSpec] = Field(default_factory=list)
    datasetName: str | None = Field(default=None, description="输出文件名，缺省按 seed 生成")


class InjectedDefect(BaseModel):
    """golden 里的单条缺陷记录 —— 评测打分的判据。"""

    code: str
    expectLevel: str = Field(description="ROW / DISTRIBUTION / COMMON_SENSE / STRUCTURE / CLARIFY")
    affectedRows: int
    rowIds: list[int] = Field(default_factory=list, description="受影响行的位置索引，超过阈值时截断")
    rowIdsTruncated: bool = Field(default=False)
    columns: list[str] = Field(default_factory=list)
    expectSignal: str = Field(description="人类可读的应检出信号，用于 case 归因")
    userAsked: bool = Field(
        default=True,
        description="用户是否会在需求里主动提到。False 表示这是 L4 发现类——"
                    "用户没问，但系统应该主动报告",
    )


class DatasetBaseline(BaseModel):
    """注入前的真实统计量，常识级校验的对照基准。"""

    patientCount: int
    admissionCount: int
    admissionsPerPatient: float


class DatasetGenerateResponse(BaseModel):
    datasetPath: str
    goldenPath: str
    rowCount: int
    seed: int
    baseline: DatasetBaseline
    injected: list[InjectedDefect]
    expectClarifications: list[str] = Field(
        default_factory=list, description="该问的澄清点"
    )
    expectNoClarifications: list[str] = Field(
        default_factory=list,
        description="不该问的点，澄清恰当率的反向计分依据",
    )
