<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/client'
import MetricValue from '../components/MetricValue.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { METRIC_NOTES } from '../constants'

/**
 * 总览：系统自检、知识库规模、最近一轮指标。/api/smoke/all 会真调一次大模型，所以
 * 是「点击才跑」；其余是纯查库，进页面直接取。
 */

const smoke = ref({ data: null, error: null, loading: false, ran: false })

async function runSmoke() {
  smoke.value = { data: null, error: null, loading: true, ran: true }
  try { smoke.value.data = await api.smokeAll() }
  catch (error) { smoke.value.error = error.message }
  finally { smoke.value.loading = false }
}

const knowledge = ref({ count: null, error: null })
const runs = ref({ data: null, error: null })

const latest = computed(() => runs.value.data?.[0] ?? null)

/**
 * 总览只给 changeNote 的摘要。它是后端 data_agent_eval_run.change_note 的原文，
 * 一轮的完整改动记录有上千字，全文在评测报告页。
 */
const latestChangeBrief = computed(() => {
  const text = (latest.value?.changeNote ?? '').trim()
  return text.length > 110 ? text.slice(0, 110) + '…' : text
})

/** 冒烟四项：字符串里带 FAILED 前缀就是失败，是后端 all() 的约定 */
function smokeState(value) {
  if (value === null || value === undefined) return 'FAILED'
  const text = typeof value === 'string' ? value : JSON.stringify(value)
  return text.startsWith('FAILED') ? 'FAILED' : 'SUCCESS'
}

function smokeText(value) {
  if (value === null || value === undefined) return '（无返回）'
  return typeof value === 'string' ? value : JSON.stringify(value)
}

onMounted(async () => {
  try { knowledge.value.count = await api.knowledgeCount() }
  catch (error) { knowledge.value.error = error.message }
  try { runs.value.data = await api.evalRuns() }
  catch (error) { runs.value.error = error.message }
})
</script>

<template>
  <div class="stack">
    <div class="card">
      <h1>临床研究数据处理 Agent</h1>
      <p class="secondary lede">
        用自然语言描述数据处理需求，系统完成画像、规划、沙箱执行与三层校验，
        并对<strong>需要临床判断的决策点主动澄清</strong>。
      </p>

    </div>

    <div class="two">
      <!-- 知识库规模 -->
      <div class="card">
        <div class="card-head">
          <h2>知识库</h2>
          <RouterLink to="/knowledge">三态检索演示 →</RouterLink>
        </div>
        <div v-if="knowledge.error" class="error">{{ knowledge.error }}</div>
        <template v-else>
          <div class="big tnum">{{ knowledge.count === null ? '…' : knowledge.count }}
            <span class="unit">条生效规则</span>
          </div>
          <p v-if="knowledge.count === 0" class="error small">
            返回 0 = 种子数据没导入，整条链路会全程走 NO_EVIDENCE。
            跑 <code>sql/data/knowledge_rule_seed.sql</code>。
          </p>
          <p v-else class="small muted">
            覆盖生理指标区间、四套预警评分分档、文本取值映射、缺失语义四类规则。
          </p>
        </template>
      </div>

      <!-- 系统自检 -->
      <div class="card">
        <div class="card-head">
          <h2>系统自检</h2>
          <button class="ghost" :disabled="smoke.loading" @click="runSmoke">
            {{ smoke.loading ? '自检中…' : '点击运行' }}
          </button>
        </div>
        <p class="small muted" style="margin-top: 0;">
          ⚠ 这一项<strong>会真调一次大模型</strong>（约 1–2 秒），所以点击才跑。
        </p>
        <div v-if="smoke.error" class="error">{{ smoke.error }}</div>
        <table v-else-if="smoke.data" class="data" style="margin-top: 8px;">
          <tbody>
            <tr v-for="(value, key) in smoke.data" :key="key">
              <td style="width: 130px;"><code>{{ key }}</code></td>
              <td><StatusBadge :kind="smokeState(value)" /></td>
              <td class="small secondary clip">{{ smokeText(value) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else-if="!smoke.ran" class="small muted">四项：Spring 容器 / Prompt 渲染 / Python 工具服务 / 模型调用。</p>
      </div>
    </div>

    <!-- 最近一轮 -->
    <div class="card">
      <div class="card-head">
        <h2>最近一轮评测</h2>
        <RouterLink to="/eval">完整指标序列 →</RouterLink>
      </div>

      <div v-if="runs.error" class="error">{{ runs.error }}</div>
      <p v-else-if="!latest" class="muted">还没有跑过评测。</p>
      <template v-else>
        <div class="row" style="gap: 12px; margin-bottom: 10px;">
          <RouterLink :to="'/eval/' + latest.runCode"><code>{{ latest.runCode }}</code></RouterLink>
          <span class="small muted">{{ latest.startTime }}</span>
          <span class="small muted">模型 {{ latest.modelName }} · seed {{ latest.datasetSeed }}</span>
        </div>
        <p v-if="latestChangeBrief" class="small secondary change" :title="latest.changeNote">
          {{ latestChangeBrief }}
        </p>

        <div class="metric-grid">
          <div class="metric">
            <div class="metric-label">通过</div>
            <div class="metric-value tnum">
              {{ latest.casePassed }}<span class="muted">/{{ latest.caseTotal }}</span>
            </div>
          </div>
          <div class="metric">
            <div class="metric-label">执行成功率</div>
            <div class="metric-value"><MetricValue :value="latest.execSuccessRate" /></div>
          </div>
          <div class="metric">
            <div class="metric-label">结果正确率</div>
            <div class="metric-value"><MetricValue :value="latest.resultCorrectRate" /></div>
          </div>
          <div class="metric">
            <div class="metric-label">澄清恰当率</div>
            <div class="metric-value"><MetricValue :value="latest.clarifyPrecision" /></div>
          </div>
          <div class="metric">
            <div class="metric-label">常识级检出</div>
            <div class="metric-value"><MetricValue :value="latest.detectRateSense" /></div>
            <div class="metric-note small muted">这一层的低值是能力边界的位置，不是一个失败的数字</div>
          </div>
          <div class="metric">
            <div class="metric-label">延迟 P50</div>
            <div class="metric-value"><MetricValue :value="latest.latencyP50Millis" format="ms" /></div>
          </div>
          <div class="metric">
            <div class="metric-label">Token 总量</div>
            <div class="metric-value">
              <MetricValue :value="latest.tokenTotal" format="int"
                           :unmeasured-hint="METRIC_NOTES.tokenTotal" />
            </div>
          </div>
        </div>
      </template>
    </div>

  </div>
</template>

<style scoped>
.lede { max-width: 82ch; margin: 8px 0 18px; }


.two {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 16px;
}

.big { font-size: 34px; font-weight: 600; line-height: 1.2; }
.big .unit { font-size: 13px; font-weight: 400; color: var(--ink-2); margin-left: 6px; }

.change {
  border-left: 3px solid var(--series-1);
  padding: 6px 12px;
  background: var(--surface-2);
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
  max-width: 90ch;
  line-height: 1.55;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 12px;
}
.metric {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 10px 12px;
}
.metric-label { font-size: 12px; color: var(--ink-2); }
.metric-value { font-size: 20px; font-weight: 600; margin-top: 2px; }
.metric-note { margin-top: 4px; line-height: 1.45; }


.clip { max-width: 420px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.error { color: var(--status-critical); }
code {
  background: var(--surface-2);
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 0.92em;
}
</style>
