"""M2：沙箱静态检查与执行测试。

这一组测试的定位跟别的不一样：**它同时是攻击面演示的素材**。
每个被拦截的用例都对应一种真实的逃逸手法，注释里写清楚它想干什么。

覆盖两件事：
1. 危险代码**不被执行**（不是"执行了但失败了"——那是两回事）；
2. 正常代码能跑通，且拦截规则不误伤合法写法。
"""

import pandas as pd
import pytest

from data_tools.dataset_generate import generate_dataset
from data_tools.sandbox import execute_code
from data_tools.schemas.dataset import DatasetGenerateRequest, DefectSpec
from data_tools.schemas.execute import ExecuteRequest
from data_tools.static_check import check_code


# ── 静态检查（第一道防线，不启动子进程） ──

_VALID = """
import pandas as pd

def clean(df, params):
    return df.drop_duplicates()
"""


def test_valid_code_passes():
    assert check_code(_VALID).passed


@pytest.mark.parametrize("code, keyword", [
    # 最直白的一种：拿 os 执行系统命令
    ("import os\ndef clean(df, params):\n    return df", "os"),
    ("import subprocess\ndef clean(df, params):\n    return df", "subprocess"),
    ("import socket\ndef clean(df, params):\n    return df", "socket"),
    ("import sys\ndef clean(df, params):\n    return df", "sys"),
    ("from os import path\ndef clean(df, params):\n    return df", "os"),
    # requests 不在白名单——数据外泄的第一步
    ("import requests\ndef clean(df, params):\n    return df", "requests"),
])
def test_blocks_non_whitelisted_import(code, keyword):
    result = check_code(code)
    assert not result.passed
    assert any(keyword in item for item in result.violations)


@pytest.mark.parametrize("snippet", [
    "eval('1+1')",
    "exec('x=1')",
    "compile('x', '<s>', 'exec')",
    "open('/etc/passwd')",
    "__import__('os')",
    # 用 getattr 拼字符串绕过静态检查的属性名判断，是标准手法
    "getattr(df, 'to_' + 'csv')('/tmp/x.csv')",
])
def test_blocks_escape_primitives(snippet):
    code = f"def clean(df, params):\n    {snippet}\n    return df"
    result = check_code(code)
    assert not result.passed, f"未拦截：{snippet}"


def test_blocks_dunder_attribute_escape():
    """经典逃逸链：从任意对象爬到 object 的所有子类，再摸出 subprocess。

    ``().__class__.__base__.__subclasses__()`` —— 只要放行 dunder 属性访问，
    白名单 import 就形同虚设。所以 dunder 一刀切全禁。
    """
    code = (
        "def clean(df, params):\n"
        "    cls = ().__class__.__base__.__subclasses__()\n"
        "    return df\n"
    )
    result = check_code(code)
    assert not result.passed
    assert any("__class__" in item or "内部属性" in item for item in result.violations)


@pytest.mark.parametrize("snippet", [
    "df.to_csv('out.csv')",
    "df.to_parquet('out.parquet')",
    "pd.read_csv('other.csv')",
    "df.to_sql('t', 'conn')",
])
def test_blocks_file_io(snippet):
    """业务代码一律不碰文件。

    读写 parquet 由沙箱宿主完成，所以这里可以无条件全禁，
    而不必去判断「这个路径允不允许」——路径白名单正是最容易被 ../ 绕过的那类防护。
    """
    code = f"import pandas as pd\ndef clean(df, params):\n    {snippet}\n    return df"
    assert not check_code(code).passed


def test_blocks_missing_entry_function():
    result = check_code("import pandas as pd\nx = 1\n")
    assert not result.passed
    assert any("clean" in item for item in result.violations)


def test_blocks_wrong_entry_signature():
    result = check_code("def clean(df):\n    return df\n")
    assert not result.passed
    assert any("2 个参数" in item for item in result.violations)


def test_blocks_wrong_second_arg_name():
    """第二个参数必须叫 params —— 名字对不上，参数注入通道就断了。"""
    result = check_code("def clean(df, extra):\n    return df\n")
    assert not result.passed
    assert any("params" in item for item in result.violations)


def test_syntax_error_is_blocked_not_executed():
    result = check_code("def clean(df)\n    return df\n")
    assert not result.passed
    assert "语法错误" in result.violations[0]


def test_all_violations_returned_at_once():
    """一次返回全部违规项。

    撞到第一条就停的话，自修复要来回三四轮才能改干净，
    每一轮都是一次完整的模型调用。
    """
    code = (
        "import os\n"
        "import socket\n"
        "def clean(df, params):\n"
        "    eval('1')\n"
        "    return df\n"
    )
    result = check_code(code)
    assert len(result.violations) >= 3


def test_list_remove_is_not_false_flagged():
    """``list.remove()`` 是合法用法，不能被 os.remove 的黑名单误伤。

    误报比漏报更隐蔽的危害在于：它会逼着模型反复重写本来正确的代码，
    烧 Token 还降低执行成功率，而日志里看起来一切正常。
    """
    code = (
        "def clean(df, params):\n"
        "    cols = list(df.columns)\n"
        "    cols.remove('体温')\n"
        "    return df[cols]\n"
    )
    assert check_code(code).passed


# ── 阈值参数通道 —— 「阈值不进 Prompt」的可机械验证部分 ──

_HARDCODED = (
    "import pandas as pd\n"
    "def clean(df, params):\n"
    "    df.loc[(df['体温'] < 32) | (df['体温'] > 45), '体温'] = None\n"
    "    return df\n"
)

_PARAMETERIZED = (
    "import pandas as pd\n"
    "def clean(df, params):\n"
    "    low, high = params['体温_min'], params['体温_max']\n"
    "    df.loc[(df['体温'] < low) | (df['体温'] > high), '体温'] = None\n"
    "    return df\n"
)


def test_hardcoded_threshold_is_rejected_when_params_supplied():
    """带了临床参数却把阈值写死 → 拦截。

    这条检查把「阈值不进 Prompt」从一句约定变成可机械验证的门禁。
    写死的后果不是跑不通——它照样跑得通、结果看着也对，
    只是院内规范改版后系统会继续用旧数字，而且没有任何迹象。
    """
    result = check_code(_HARDCODED, require_params=True)
    assert not result.passed
    assert any("params" in item for item in result.violations)


def test_parameterized_threshold_passes():
    assert check_code(_PARAMETERIZED, require_params=True).passed


def test_params_not_required_when_no_params_supplied():
    """不带参数的步骤（如去重）不受这条约束——去重本来就没有临床阈值。"""
    assert check_code(_HARDCODED, require_params=False).passed


def test_assigning_params_does_not_count_as_using_it():
    """``params = {}`` 是在架空参数通道，不算「用了 params」。"""
    code = (
        "def clean(df, params):\n"
        "    params = {'体温_min': 32}\n"
        "    return df\n"
    )
    assert not check_code(code, require_params=True).passed


# ── 实际执行（第二、三道防线） ──

@pytest.fixture(scope="module")
def dataset_path():
    response = generate_dataset(DatasetGenerateRequest(
        rows=2000, patients=150, seed=5,
        defects=[DefectSpec(code="ROW_DUPLICATE", ratio=0.05)],
        datasetName="sb_input"))
    return response.datasetPath


def test_execute_dedup_end_to_end(dataset_path):
    """L1-01 的核心动作：去重。跑完要能读出结果 parquet 且行数真的变少。"""
    before = len(pd.read_parquet(dataset_path))
    response = execute_code(ExecuteRequest(
        taskId="T-sandbox-ok",
        code="import pandas as pd\n\ndef clean(df, params):\n    return df.drop_duplicates()\n",
        inputPath=dataset_path,
        timeoutSeconds=120,
    ))
    assert response.success, response.stderr
    assert response.blockedReason is None
    assert response.outputRowCount < before
    assert len(pd.read_parquet(response.outputPath)) == response.outputRowCount


def test_execute_injects_params_at_runtime(dataset_path):
    """阈值在运行时才注入，端到端验证参数通道真的通了。

    同一份代码、两组不同的 params，结果必须不同——
    这证明生效的是传进来的阈值，而不是代码里写死的数字。
    """
    def nulled(low, high):
        response = execute_code(ExecuteRequest(
            taskId="T-sandbox-params",
            code=_PARAMETERIZED,
            params={"体温_min": low, "体温_max": high},
            inputPath=dataset_path,
        ))
        assert response.success, response.stderr
        return pd.read_parquet(response.outputPath)["体温"].isna().sum()

    wide = nulled(32.0, 45.0)
    narrow = nulled(36.5, 37.0)
    assert narrow > wide


def test_execute_blocked_code_never_runs(dataset_path, tmp_path):
    """被拦截 = 根本没执行，不是「执行了但失败了」。

    判据是那个本该被写出来的文件不存在。
    如果只断言 success=false，代码跑过一遍再报错也能通过——那不叫拦截。
    """
    marker = tmp_path / "escaped.txt"
    code = (
        "import os\n"
        "def clean(df, params):\n"
        f"    os.makedirs(r'{marker.parent}', exist_ok=True)\n"
        f"    open(r'{marker}', 'w').write('escaped')\n"
        "    return df\n"
    )
    response = execute_code(ExecuteRequest(
        taskId="T-sandbox-blocked", code=code, inputPath=dataset_path))

    assert response.success is False
    assert response.blockedReason is not None
    assert response.blockedDetail
    assert response.exitCode is None, "有退出码说明子进程真的启动了"
    assert not marker.exists(), "危险代码被执行了，第一道防线失效"


def test_execute_runtime_error_is_reported_not_swallowed(dataset_path):
    """运行时报错要把 traceback 原样带回来 —— 那是自修复的输入。"""
    response = execute_code(ExecuteRequest(
        taskId="T-sandbox-err",
        code="import pandas as pd\n\ndef clean(df, params):\n    return df['不存在的列']\n",
        inputPath=dataset_path,
    ))
    assert response.success is False
    assert response.blockedReason is None, "这是运行期失败，不该报成静态拦截"
    assert "KeyError" in response.stderr


def test_execute_rejects_non_dataframe_return(dataset_path):
    response = execute_code(ExecuteRequest(
        taskId="T-sandbox-badret",
        code="def clean(df, params):\n    return len(df)\n",
        inputPath=dataset_path,
    ))
    assert response.success is False
    assert "DataFrame" in response.stderr


def test_execute_timeout_kills_process(dataset_path):
    """死循环必须被杀掉。

    这条正是「为什么不能在本进程 exec」的直接证据：
    线程杀不掉，只有子进程能被 SIGKILL。
    """
    response = execute_code(ExecuteRequest(
        taskId="T-sandbox-timeout",
        code="def clean(df, params):\n    while True:\n        pass\n    return df\n",
        inputPath=dataset_path,
        timeoutSeconds=3,
    ))
    assert response.success is False
    assert response.timedOut is True
    assert response.durationMs >= 3000


def test_runtime_import_guard_is_second_line_of_defence(dataset_path):
    """即使绕过静态检查，运行时 __import__ 仍然拦得住。

    这里直接把 dryRun 关掉、用 exec 之外的路径构造——
    实际做法是让静态检查看不见的动态导入在运行期炸掉。
    两道防线独立生效：静态检查会被新语法绕过，受限 builtins 不会。
    """
    from data_tools.sandbox_runner import _guarded_import

    with pytest.raises(ImportError, match="沙箱禁止导入"):
        _guarded_import("os")
    # 白名单模块照常放行
    assert _guarded_import("math") is not None


def test_dry_run_does_not_execute(dataset_path):
    """dryRun 只跑静态检查，用于把「拦截能力」单独演示出来。"""
    response = execute_code(ExecuteRequest(
        taskId="T-sandbox-dry",
        code="import pandas as pd\n\ndef clean(df, params):\n    return df\n",
        inputPath="/不存在的路径.parquet",
        dryRun=True,
    ))
    assert response.success is True
    assert response.outputRowCount is None


def test_task_id_cannot_escape_work_root(dataset_path):
    """taskId 来自外部，不能靠它写到沙箱目录之外。"""
    response = execute_code(ExecuteRequest(
        taskId="../../escape",
        code="import pandas as pd\n\ndef clean(df, params):\n    return df.head(10)\n",
        inputPath=dataset_path,
    ))
    assert response.success
    assert ".." not in str(response.outputPath)


# ────────────────────────────────────────────────
# ── 两道防线的口径必须对得上 ──
# ────────────────────────────────────────────────

def test_fullwidth_conversion_builtins_available(dataset_path):
    """全半角归一的标准写法（chr/ord 码位平移）必须真的能跑。

    静态检查用黑名单、运行时用白名单，落在两者之间的名字会静态通过、运行时报 NameError。
    模型写的是完全正确的 Python，静态检查也放行了，
    但运行时白名单里没有 ord —— 而且自修复三轮都改不掉，
    因为模型没有任何理由怀疑一个内置函数不存在。

    这条测试锁住的不是「ord 可用」这个事实，是**这类失败不该再出现**：
    静态检查放行的代码，不能在运行时因为缺内置名字而失败。
    """
    code = (
        "import pandas as pd\n"
        "\n"
        "def clean(df, params):\n"
        "    def to_half(text):\n"
        "        if not isinstance(text, str):\n"
        "            return text\n"
        "        return ''.join(\n"
        "            chr(ord(c) - 0xFEE0) if 0xFF01 <= ord(c) <= 0xFF5E else c\n"
        "            for c in text\n"
        "        )\n"
        "    for column in df.columns:\n"
        '        if df[column].dtype == "object":\n'
        "            df[column] = df[column].map(to_half)\n"
        "    return df\n"
    )
    response = execute_code(ExecuteRequest(
        taskId="T-sandbox-fullwidth", code=code, inputPath=dataset_path))
    assert response.success is True, response.stderr


def test_static_blacklist_and_runtime_whitelist_do_not_contradict():
    """静态检查禁掉的名字，不能同时出现在运行时白名单里。

    两道防线各自维护一张名单：静态检查是黑名单，运行时是白名单。
    两张名单朝相反方向生长，一旦出现交集就说明有一层的策略是空转的——
    静态检查拦下的东西，运行时却准备好了给它用。

    注意这条测试拦不住反向的漏洞（名字既不在黑名单、也不在白名单，
    于是静态通过、运行时炸），那正是 ord 踩到的那种。
    要机械地拦住它，静态检查得知道全部可用名字（含局部变量、推导式变量、
    import 别名），代价远高于收益。所以那一侧靠上面那条实测用例守着。
    """
    from data_tools.sandbox_runner import _SAFE_BUILTIN_NAMES
    from data_tools.static_check import FORBIDDEN_NAMES

    overlap = FORBIDDEN_NAMES & set(_SAFE_BUILTIN_NAMES)
    assert not overlap, f"这些名字被静态检查禁掉，却在运行时白名单里: {overlap}"
