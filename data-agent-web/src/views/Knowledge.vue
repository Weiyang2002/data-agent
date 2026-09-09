<script setup>
import { onMounted, ref } from 'vue'
import { api, ApiError } from '../api/client'
import StatusBadge from '../components/StatusBadge.vue'

/**
 * 知识库检索页。一屏之内把 RESOLVED / AMBIGUOUS / NO_EVIDENCE 三种结局同时摆出来。
 */

const RULE_TYPES = [
  { value: 'VALIDITY', label: 'VALIDITY · 有效性区间或合法取值' },
  { value: 'SEVERITY_SCORING', label: 'SEVERITY_SCORING · 预警评分分档' },
  { value: 'TEXT_MAPPING', label: 'TEXT_MAPPING · 文本取值映射' },
  { value: 'MISSING_SEMANTICS', label: 'MISSING_SEMANTICS · 缺失语义' }
]

const PRESETS = [
  {
    column: '体温', ruleType: 'VALIDITY',
    expect: 'RESOLVED',
    why: '命中唯一一条规则，自动决定，ruleId 记为依据'
  },
  {
    column: '体温', ruleType: 'SEVERITY_SCORING',
    expect: 'AMBIGUOUS',
    why: 'NEWS / MEWS / SEWS 三套标准同时命中，转澄清由医生选择'
  },
  {
    column: '升压药', ruleType: 'MISSING_SEMANTICS',
    expect: 'NO_EVIDENCE',
    why: '知识库无此项记录，短路返回无依据，不继续推断'
  },
  {
    column: 'T', ruleType: 'VALIDITY',
    expect: 'RESOLVED',
    why: '别名对齐：T → 体温，与第一条命中同一规则'
  }
]

const presetResults = ref(PRESETS.map(p => ({ ...p, data: null, error: null, loading: true })))

async function runPresets() {
  presetResults.value = PRESETS.map(p => ({ ...p, data: null, error: null, loading: true }))
  await Promise.all(presetResults.value.map(async (item, index) => {
    try { presetResults.value[index].data = await api.knowledgeProbe(item.column, item.ruleType) }
    catch (error) { presetResults.value[index].error = error.message }
    finally { presetResults.value[index].loading = false }
  }))
}

// ── 自由查询 ──
const form = ref({ column: '收缩压', ruleType: 'VALIDITY' })
const probe = ref({ data: null, error: null, loading: false })

async function runProbe() {
  probe.value = { data: null, error: null, loading: true }
  try { probe.value.data = await api.knowledgeProbe(form.value.column.trim(), form.value.ruleType) }
  catch (error) {
    probe.value.error = error instanceof ApiError ? error.message : String(error)
  }
  finally { probe.value.loading = false }
}

onMounted(runPresets)
</script>

<template>
  <div class="stack">
    <div class="card">
      <div class="card-head">
        <div>
          <h1>知识库检索：三种结局</h1>
          <p class="secondary" style="margin: 6px 0 0; max-width: 76ch;">
            判据只有一条：命中条数。0 条 → 返回无依据；1 条 → 自动决定；
            多条 → 触发澄清。<strong>任何情况下不猜测。</strong>
          </p>
        </div>
        <button class="ghost" @click="runPresets">重新探测</button>
      </div>

      <div class="presets">
        <div v-for="(item, index) in presetResults" :key="index" class="preset">
          <div class="preset-head">
            <code class="query">{{ item.column }} + {{ item.ruleType }}</code>
            <StatusBadge v-if="item.data" :kind="item.data.outcome" />
            <span v-else-if="item.loading" class="muted small">探测中…</span>
            <StatusBadge v-else kind="FAILED" text="失败" />
          </div>

          <div v-if="item.error" class="error small">{{ item.error }}</div>

          <template v-else-if="item.data">
            <div class="preset-body small">
              <div><span class="muted">命中</span> <strong class="tnum">{{ item.data.hitCount }}</strong> 条<template v-if="item.data.ruleIds?.length">，ruleId <code>{{ item.data.ruleIds.join(', ') }}</code></template></div>
              <div v-if="item.data.sources?.length">
                <span class="muted">冲突来源</span> {{ item.data.sources.join(' / ') }}
              </div>
              <div class="action">{{ item.data.action }}</div>
            </div>
            <div class="why small muted">{{ item.why }}</div>
          </template>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <h2>自由查询</h2>
        <span class="hint">列名支持别名（T / 体温），归一在检索层做</span>
      </div>
      <form class="row" @submit.prevent="runProbe">
        <label class="field" style="flex: 0 0 200px;">
          <span>列名</span>
          <input type="text" v-model="form.column" placeholder="体温 / 收缩压 / 升压药" />
        </label>
        <label class="field grow" style="max-width: 400px;">
          <span>规则类型</span>
          <select v-model="form.ruleType">
            <option v-for="type in RULE_TYPES" :key="type.value" :value="type.value">{{ type.label }}</option>
          </select>
        </label>
        <button class="primary" type="submit" :disabled="probe.loading || !form.column.trim()"
                style="align-self: flex-end;">
          {{ probe.loading ? '查询中…' : '查询' }}
        </button>
      </form>

      <div v-if="probe.error" class="error" style="margin-top: 14px;">{{ probe.error }}</div>

      <div v-if="probe.data" class="result" style="margin-top: 16px;">
        <div class="row" style="gap: 14px;">
          <StatusBadge :kind="probe.data.outcome" />
          <span class="secondary">命中 <strong class="tnum">{{ probe.data.hitCount }}</strong> 条</span>
          <span v-if="probe.data.ruleIds?.length" class="secondary">
            ruleId <code>{{ probe.data.ruleIds.join(', ') }}</code>
          </span>
        </div>
        <p class="action" style="margin: 10px 0 0;">{{ probe.data.action }}</p>
        <div v-if="probe.data.sources?.length" class="small secondary" style="margin-top: 6px;">
          冲突来源：{{ probe.data.sources.join(' / ') }}
        </div>
        <p v-if="probe.data.hitCount === 0" class="small muted" style="margin: 10px 0 0;">
          这里的 0 条是<strong>正确行为</strong>，不是查询失败：
          该项没有可引用的规则，系统不会推断。
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.presets {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 12px;
}
.preset {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 12px 14px;
  background: var(--surface-1);
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.preset-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; }
.query { font-size: 12px; color: var(--ink-1); }
.preset-body { display: flex; flex-direction: column; gap: 3px; color: var(--ink-2); }
.action { color: var(--ink-1); }
.why { border-top: 1px dashed var(--grid); padding-top: 8px; margin-top: auto; }
.error { color: var(--status-critical); }
code {
  background: var(--surface-2);
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 0.92em;
}
</style>
