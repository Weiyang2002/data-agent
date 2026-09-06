"""M0 冒烟测试：/health 端点。

运行：cd data-agent-tools && python -m pytest tests -v
"""

from fastapi.testclient import TestClient

from data_tools.main import app

client = TestClient(app)


def test_health_returns_ok():
    response = client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["service"] == "data-agent-tools"
    # 沙箱目录应该能创建；创建不了说明环境有问题，此时 health 必须如实返回 False
    assert body["sandboxReady"] is True


def test_health_echoes_trace_id():
    """traceId 必须原样回传，这是跨语言链路追踪的基础。"""
    response = client.get("/health", headers={"X-Trace-Id": "T-20260819-001"})
    assert response.status_code == 200
    assert response.headers["X-Trace-Id"] == "T-20260819-001"
