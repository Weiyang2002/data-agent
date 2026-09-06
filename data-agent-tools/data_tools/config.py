"""配置读取。

- 环境变量优先于配置文件，配置文件只放默认值。
- 自己解析极简 YAML（仅「嵌套字典 + 标量」），不引入 pyyaml，不支持数组。
- 支持 Spring 风格占位符 ``${ENV_NAME:default}``。
"""

import os
import re
from pathlib import Path
from typing import Any


_CONFIG_CACHE: dict[str, Any] | None = None

_CONFIG_ENV = "DATA_TOOLS_CONFIG"
_CONFIG_FILENAME = "data-tools.yaml"


def config_value(path: str, env_name: str, default: str = "") -> str:
    """按 ``a.b.c`` 路径读配置；同名环境变量存在时优先。"""
    env_value = os.getenv(env_name)
    if env_value is not None and env_value.strip():
        return env_value.strip()
    value = _read_path(_load_config(), path.split("."))
    if value is None:
        return default
    return _resolve_placeholders(str(value).strip())


def config_int(path: str, env_name: str, default: int,
               min_value: int | None = None, max_value: int | None = None) -> int:
    value = config_value(path, env_name, "")
    if not value:
        result = default
    else:
        try:
            result = int(value)
        except ValueError:
            result = default
    if min_value is not None:
        result = max(min_value, result)
    if max_value is not None:
        result = min(max_value, result)
    return result


def config_float(path: str, env_name: str, default: float) -> float:
    value = config_value(path, env_name, "")
    if not value:
        return default
    try:
        return float(value)
    except ValueError:
        return default


def _load_config() -> dict[str, Any]:
    global _CONFIG_CACHE
    if _CONFIG_CACHE is not None:
        return _CONFIG_CACHE
    configured_path = os.getenv(_CONFIG_ENV, "").strip()
    config_path = Path(configured_path).expanduser() if configured_path else Path.cwd() / _CONFIG_FILENAME
    if not config_path.exists():
        _CONFIG_CACHE = {}
        return _CONFIG_CACHE
    text = config_path.read_text(encoding="utf-8")
    _CONFIG_CACHE = _parse_simple_yaml(text)
    return _CONFIG_CACHE


def _parse_simple_yaml(text: str) -> dict[str, Any]:
    """极简 YAML：仅支持缩进嵌套的 key: value，不支持数组与多行字符串。"""
    root: dict[str, Any] = {}
    stack: list[tuple[int, dict[str, Any]]] = [(-1, root)]
    for raw_line in text.splitlines():
        if not raw_line.strip() or raw_line.lstrip().startswith("#"):
            continue
        indent = len(raw_line) - len(raw_line.lstrip(" "))
        stripped = _strip_comment(raw_line.strip())
        if ":" not in stripped:
            continue
        key, raw_value = stripped.split(":", 1)
        key = key.strip()
        raw_value = raw_value.strip()
        while stack and indent <= stack[-1][0]:
            stack.pop()
        parent = stack[-1][1]
        if not raw_value:
            node: dict[str, Any] = {}
            parent[key] = node
            stack.append((indent, node))
            continue
        parent[key] = _parse_scalar(raw_value)
    return root


def _strip_comment(line: str) -> str:
    """去掉行尾注释，但不能误伤引号内的 # 。"""
    quote: str | None = None
    for index, char in enumerate(line):
        if char in ("'", '"'):
            quote = None if quote == char else char
        if char == "#" and quote is None:
            return line[:index].rstrip()
    return line


def _parse_scalar(value: str) -> Any:
    if (value.startswith('"') and value.endswith('"')) or (value.startswith("'") and value.endswith("'")):
        return value[1:-1]
    lowered = value.lower()
    if lowered in {"true", "false"}:
        return lowered == "true"
    if lowered in {"null", "none", "~"}:
        return None
    return value


def _resolve_placeholders(value: str) -> str:
    """解析 ``${ENV_NAME:default}`` 形式的占位符。"""

    def replace(match: re.Match[str]) -> str:
        expression = match.group(1)
        env_name, _, fallback = expression.partition(":")
        return os.getenv(env_name, fallback)

    return re.sub(r"\$\{([A-Za-z_][A-Za-z0-9_]*(?::[^}]*)?)\}", replace, value)


def _read_path(config: dict[str, Any], parts: list[str]) -> Any:
    current: Any = config
    for part in parts:
        if not isinstance(current, dict) or part not in current:
            return None
        current = current[part]
    return current
