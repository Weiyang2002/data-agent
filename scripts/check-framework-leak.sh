#!/usr/bin/env bash
# 框架隔离门禁
#
# 约束：com.alibaba.cloud.ai.* 的 import 只允许出现在
#   - pipeline/config/
#   - pipeline/executor/
# 两个包里。其余任何位置出现即视为框架泄漏。
#
# 目的：把「换框架」的成本锁定在两个包内。四个 Agent、知识库、
# 三层校验、评测体系全部只依赖自定义接口，将来迁 LangChain4j
# 或改自研编排时零改动。
#
# ────────────────────────────────────────────────────────────
# M2 修正：排除注释行
#
# 原版直接 grep 文件内容，结果把**文档注释里提到框架包名**也算成泄漏。
# M2 写 DataAgent / CodeGenPort 时，类注释里必须解释「为什么这个包
# 不能 import com.alibaba.cloud.ai」——一解释就被自己的门禁拦下来了。
#
# 这是个真实的教训：**门禁检查的对象要和它想表达的约束一致。**
# 约束说的是「import 不许出现」，不是「这几个字不许出现」。
# 检查口径比约束宽，代价是逼着人为了过门禁而删掉本该写的注释——
# 那等于门禁在惩罚好的实践。
#
# 现在的口径：排除以 * 或 // 开头的行（Javadoc 与行注释），
# 其余出现 com.alibaba.cloud.ai 的位置一律算泄漏。
# 这样既拦得住 import，也拦得住不写 import 的全限定名调用。
# ────────────────────────────────────────────────────────────
#
# 用法：bash scripts/check-framework-leak.sh
# 退出码：0 通过，1 发现泄漏

set -uo pipefail

SRC_DIR="data-agent-business/src/main/java"

if [ ! -d "$SRC_DIR" ]; then
  echo "找不到源码目录 $SRC_DIR，请在项目根目录执行"
  exit 1
fi

LEAKS=$(grep -rn "com\.alibaba\.cloud\.ai" "$SRC_DIR" 2>/dev/null \
  | grep -vE ':[[:space:]]*\*' \
  | grep -vE ':[[:space:]]*//' \
  | cut -d: -f1 \
  | sort -u \
  | grep -v "/pipeline/config/" \
  | grep -v "/pipeline/executor/" || true)

if [ -n "$LEAKS" ]; then
  echo "❌ 框架泄漏：以下文件不应依赖 SAA Graph"
  echo "$LEAKS" | sed 's/^/   /'
  echo ""
  echo "修复方式：把框架类型封装进 pipeline/config 或 pipeline/executor，"
  echo "          对外只暴露 pipeline/agent 下的自定义接口。"
  exit 1
fi

echo "✅ 框架隔离检查通过：SAA Graph 依赖未越出 config/ 与 executor/"

# 附带报告一下当前实际用到框架的位置，便于估算迁移成本。
# 「成本可量化」是这条约束的卖点，那就把数字直接打出来。
USAGES=$(grep -rn "com\.alibaba\.cloud\.ai" "$SRC_DIR" 2>/dev/null \
  | grep -vE ':[[:space:]]*\*' \
  | grep -vE ':[[:space:]]*//' \
  | cut -d: -f1 | sort -u || true)
if [ -n "$USAGES" ]; then
  echo ""
  echo "   当前依赖 SAA Graph 的文件（迁移时需要重写的全部范围）："
  echo "$USAGES" | while read -r file; do
    lines=$(wc -l < "$file")
    echo "     $file (${lines} 行)"
  done
fi

exit 0
