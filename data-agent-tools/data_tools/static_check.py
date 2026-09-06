"""沙箱第一道防线：AST 静态检查（执行前拦截危险代码，与运行时的进程隔离、超时
兜底互补）。

代码契约是 ``def clean(df: pd.DataFrame, params: dict) -> pd.DataFrame``：输入
DataFrame 从参数进、输出从返回值出，读写 parquet 全部由沙箱宿主完成。业务代码
没有正当理由碰文件，所以 ``open`` / ``read_csv`` / ``to_csv`` 无条件全禁，不做路径
白名单判断（易被 ``../`` 绕过）。

第二个参数 ``params`` 是「阈值不进 Prompt」的物理实现：临床阈值随 ``/execute``
请求传入、运行时注入，代码里写的是 ``params["体温_min"]`` 而非字面值。
``require_params`` 检查机械地验证这条约束没被绕过。

检查项：语法可解析、import 顶层模块在白名单内、禁止逃逸原语
（``eval/exec/compile/open/__import__/getattr`` 等）、禁止 dunder 属性访问、禁止文件
IO 方法、必须定义 ``clean(df, params)``、带参数时代码必须真的引用 ``params``。
违规项一次性全部返回。
"""

from __future__ import annotations

import ast

# 白名单模块，是「顶层模块名」（import pandas.api.types 的顶层是 pandas，允许）。
ALLOWED_MODULES = frozenset({"pandas", "numpy", "datetime", "re", "math"})

# 逃逸原语。getattr/setattr 是「用字符串拼出属性名绕过静态检查」的标准手法。
# object/type/super：``object.__subclasses__()`` 是沙箱逃逸经典入口，在静态层拦下并
# 在报错里给出等价写法（模型会写 ``df[c].dtype == object`` 这类正当代码，见 _NAME_HINTS）。
FORBIDDEN_NAMES = frozenset({
    "eval", "exec", "compile", "open", "__import__", "input",
    "globals", "locals", "vars", "getattr", "setattr", "delattr",
    "exit", "quit", "breakpoint", "help", "memoryview",
    "object", "type", "super",
})

# 被禁名字的替代写法。只说「禁止使用 X」的报错对模型是死路，门禁拦下一种写法时
# 有义务说出被认可的那种。
_NAME_HINTS = {
    "object": '判断文本列请用字符串形式的 dtype：df[col].dtype == "object"，'
              "或 df.select_dtypes(include=\"object\")；"
              "裸的 object 是逃逸原语，沙箱不提供",
    "type": "判断类型请用 isinstance(x, str) 或 pandas 的 dtype 比较",
    "super": "生成的清洗代码里不应出现类继承",
}

# 文件/网络 IO 方法。全禁，不做路径判断。
FORBIDDEN_ATTRIBUTES = frozenset({
    "to_csv", "to_parquet", "to_pickle", "to_excel", "to_json", "to_sql",
    "to_hdf", "to_feather", "to_clipboard", "to_stata", "to_xml", "to_latex",
    "read_csv", "read_parquet", "read_pickle", "read_excel", "read_json",
    "read_sql", "read_hdf", "read_feather", "read_clipboard", "read_table",
    "read_html", "read_fwf", "read_stata", "read_xml", "read_orc", "read_sas",
    # np.load 默认能反序列化 pickle，等价于任意代码执行，必须一起禁
    "tofile", "fromfile", "load", "save", "savez", "savetxt", "loadtxt", "memmap",
    "system", "popen", "spawn", "fork",
})
# 刻意不放进黑名单的：remove / unlink / rmdir / makedirs。它们只存在于 os 模块上
# （import os 已在上一道被拦），而 list.remove() 是正当用法，放进来会误伤合法代码。

ENTRY_FUNCTION = "clean"
ENTRY_ARGS = ("df", "params")


class StaticCheckResult:
    def __init__(self, violations: list[str]):
        self.violations = violations

    @property
    def passed(self) -> bool:
        return not self.violations

    @property
    def reason(self) -> str | None:
        if self.passed:
            return None
        return f"静态检查未通过，共 {len(self.violations)} 项违规"


def check_code(code: str, require_params: bool = False) -> StaticCheckResult:
    """静态检查。``require_params=True`` 表示本次执行带了临床参数，此时代码必须真的
    引用 ``params``，否则说明模型把数字写死在代码里了。
    """
    try:
        tree = ast.parse(code)
    except SyntaxError as error:
        return StaticCheckResult([
            f"语法错误：第 {error.lineno} 行 {error.msg}"
        ])

    visitor = _Visitor()
    visitor.visit(tree)
    violations = visitor.violations

    entry = _find_entry(tree)
    if entry is None:
        violations.append(
            f"必须定义顶层函数 {ENTRY_FUNCTION}(df, params)；"
            f"沙箱负责读写 parquet，代码只处理传入的 DataFrame"
        )
    else:
        names = [arg.arg for arg in entry.args.args]
        if len(names) != len(ENTRY_ARGS):
            violations.append(
                f"{ENTRY_FUNCTION} 必须恰好接收 {len(ENTRY_ARGS)} 个参数 "
                f"{ENTRY_ARGS}，当前是 {len(names)} 个"
            )
        elif names[1] != ENTRY_ARGS[1]:
            violations.append(
                f"{ENTRY_FUNCTION} 的第 2 个参数必须命名为 "
                f"{ENTRY_ARGS[1]}，当前是 {names[1]}"
            )

    if require_params and not visitor.uses_params:
        violations.append(
            f"本次执行带了临床参数，但代码从未引用 {ENTRY_ARGS[1]}。"
            f"阈值、取值映射等临床数字必须从 {ENTRY_ARGS[1]} 取，不得写死在代码里——"
            f"写死会让「规范遵从」变成不可验证的说法，且院内规范改版后代码不会跟着变"
        )

    return StaticCheckResult(violations)


def _find_entry(tree: ast.Module) -> ast.FunctionDef | None:
    for node in tree.body:
        if isinstance(node, ast.FunctionDef) and node.name == ENTRY_FUNCTION:
            return node
    return None


class _Visitor(ast.NodeVisitor):

    def __init__(self) -> None:
        self.violations: list[str] = []
        self.uses_params = False

    def _flag(self, node: ast.AST, message: str) -> None:
        line = getattr(node, "lineno", "?")
        self.violations.append(f"第 {line} 行：{message}")

    def visit_Import(self, node: ast.Import) -> None:
        for alias in node.names:
            root = alias.name.split(".")[0]
            if root not in ALLOWED_MODULES:
                self._flag(node, f"禁止 import {alias.name}；"
                                 f"白名单仅 {'/'.join(sorted(ALLOWED_MODULES))}")
        self.generic_visit(node)

    def visit_ImportFrom(self, node: ast.ImportFrom) -> None:
        # from . import x 的 module 是 None，相对导入一律拒绝
        module = node.module or ""
        root = module.split(".")[0]
        if root not in ALLOWED_MODULES:
            self._flag(node, f"禁止 from {module or '.'} import ...；"
                             f"白名单仅 {'/'.join(sorted(ALLOWED_MODULES))}")
        self.generic_visit(node)

    def visit_Name(self, node: ast.Name) -> None:
        if node.id in FORBIDDEN_NAMES:
            hint = _NAME_HINTS.get(node.id)
            self._flag(node, f"禁止使用 {node.id}" + (f"；{hint}" if hint else ""))
        # 只认「读」：写 params 不算用了它（params = {} 反而是在架空参数通道）
        if node.id == ENTRY_ARGS[1] and isinstance(node.ctx, ast.Load):
            self.uses_params = True
        self.generic_visit(node)

    def visit_Attribute(self, node: ast.Attribute) -> None:
        name = node.attr
        if name.startswith("__") and name.endswith("__"):
            # 所有 Python 沙箱逃逸都要经过 dunder 属性，这里一刀切
            self._flag(node, f"禁止访问内部属性 {name}")
        elif name in FORBIDDEN_ATTRIBUTES:
            self._flag(node, f"禁止调用 {name}；沙箱不允许业务代码碰文件或系统调用，"
                             f"读写 parquet 由宿主完成")
        self.generic_visit(node)
