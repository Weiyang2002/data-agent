"""沙箱子进程入口。

这个文件是独立脚本，不 import 任何 data_tools 模块：父进程用 ``python -I`` 启动它
（isolated 模式），任何对项目内模块的依赖都会断掉。

协议：stdin 收一行 JSON ``{codePath, inputPath, outputPath, memoryLimitMb, params}``，
结束时在 stdout 打一行 ``__DATA_AGENT_RESULT__ {json}``（哨兵前缀，让父进程把结果
和业务代码的 print 分开）。

``params`` 是临床阈值等参数的注入通道，作为 ``clean(df, params)`` 的第二个实参，
不出现在代码生成的 Prompt 里。
"""

import json
import sys
import traceback

RESULT_MARKER = "__DATA_AGENT_RESULT__"
ENTRY_FUNCTION = "clean"

# 允许业务代码使用的内置函数。AST 静态检查之外的第二重保险：即使绕过静态检查，
# 运行时也拿不到 open / eval / __import__。
#
# 注意静态检查用黑名单（FORBIDDEN_NAMES）、运行时用白名单（本表），落在两者之间
# 的名字会静态通过、运行时炸。ord / chr 在此白名单里（全半角归一按码位平移要用），
# 它们是纯值函数，不接触文件、网络、对象内部。
_SAFE_BUILTIN_NAMES = [
    "abs", "all", "any", "bool", "chr", "dict", "divmod", "enumerate", "filter",
    "float", "format", "frozenset", "int", "isinstance", "issubclass", "iter",
    "len", "list", "map", "max", "min", "next", "ord", "print", "range", "repr",
    "reversed", "round", "set", "slice", "sorted", "str", "sum", "tuple",
    "zip", "True", "False", "None", "Exception", "ValueError", "KeyError",
    "TypeError", "IndexError", "ZeroDivisionError",
]

_ALLOWED_MODULES = {"pandas", "numpy", "datetime", "re", "math"}


def _guarded_import(name, globals=None, locals=None, fromlist=(), level=0):
    """只放行白名单模块的 __import__，防住静态检查没覆盖到的导入写法。"""
    root = name.split(".")[0]
    if root not in _ALLOWED_MODULES:
        raise ImportError(f"沙箱禁止导入模块: {name}")
    return __import__(name, globals, locals, fromlist, level)


def _build_safe_builtins():
    import builtins
    safe = {name: getattr(builtins, name) for name in _SAFE_BUILTIN_NAMES
            if hasattr(builtins, name)}
    safe["__import__"] = _guarded_import
    return safe


def _apply_resource_limits(memory_limit_mb):
    """进程级资源限制，返回是否真的限制住了（返回值必须如实）。Windows 上没有
    resource 模块，返回 False。
    """
    try:
        import resource
    except ImportError:
        return False
    limit_bytes = memory_limit_mb * 1024 * 1024
    resource.setrlimit(resource.RLIMIT_AS, (limit_bytes, limit_bytes))
    return True


def _force_utf8_streams():
    """把三个标准流锁成 UTF-8。

    ``-I`` 启动的子进程会忽略 ``PYTHONIOENCODING``，stdin/stdout 回落到系统区域
    设置（中文 Windows 上是 cp936）。父进程写 UTF-8、子进程按 cp936 解码，``params``
    里的中文列名会变成 U+FFFD，代码取 ``params['体温_min']`` 直接 KeyError。
    """
    for stream in (sys.stdin, sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass


def main():
    _force_utf8_streams()
    payload = json.loads(sys.stdin.read())
    result = {
        "ok": False,
        "error": None,
        "outputRowCount": None,
        "outputColumnCount": None,
        "memoryLimitEnforced": False,
    }
    try:
        result["memoryLimitEnforced"] = _apply_resource_limits(
            int(payload.get("memoryLimitMb", 2048)))

        import pandas as pd

        with open(payload["codePath"], "r", encoding="utf-8") as handle:
            code = handle.read()
        frame = pd.read_parquet(payload["inputPath"])

        namespace = {"__builtins__": _build_safe_builtins(), "__name__": "sandbox"}
        exec(compile(code, "<generated>", "exec"), namespace)

        entry = namespace.get(ENTRY_FUNCTION)
        if not callable(entry):
            raise RuntimeError(f"代码未定义可调用的 {ENTRY_FUNCTION}(df)")

        output = entry(frame, dict(payload.get("params") or {}))
        if not isinstance(output, pd.DataFrame):
            raise TypeError(
                f"{ENTRY_FUNCTION}(df, params) 必须返回 DataFrame，"
                f"实际返回 {type(output).__name__}"
            )

        output.to_parquet(payload["outputPath"], index=False)
        result["ok"] = True
        result["outputRowCount"] = int(len(output))
        result["outputColumnCount"] = int(len(output.columns))
    except BaseException:
        # 捕 BaseException 而不是 Exception：内存超限抛 MemoryError，分配失败可能是
        # SystemError，这些都要作为「执行失败」回传
        result["error"] = traceback.format_exc()
    finally:
        # 哨兵行用 ensure_ascii=True：它是「子进程跑没跑完」的唯一判据，纯 ASCII
        # 一定能穿过流编码问题；json.loads 会把 \uXXXX 还原成中文
        sys.stdout.write("\n" + RESULT_MARKER + " " + json.dumps(result, ensure_ascii=True) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
