"""临床研究数据处理 Agent —— Python 工具服务。

职责边界：
本服务只提供确定性能力（数据画像、沙箱执行、规则校验、缺陷注入），
不做任何业务决策。所有编排、状态管理、澄清判定都在 Java 侧。
"""

SERVICE_NAME = "data-agent-tools"
SERVICE_VERSION = "0.1.0"
