<script setup>
import { onMounted, ref } from 'vue'
import { api } from './api/client'

const NAV = [
  { to: '/', label: '总览', exact: true },
  { to: '/knowledge', label: '知识库检索' },
  { to: '/task', label: '处理任务' },
  { to: '/eval', label: '评测序列' }
]

/**
 * 顶栏心跳。用 /knowledge/count 而非 /smoke/all（后者每次真调一次大模型），顺带
 * 能反映知识库种子有没有导入（返回 0 就是没导）。
 */
const health = ref({ state: 'checking', ruleCount: null, message: '' })

async function checkHealth() {
  health.value = { state: 'checking', ruleCount: null, message: '' }
  try {
    const count = await api.knowledgeCount()
    health.value = count > 0
      ? { state: 'up', ruleCount: count, message: `知识库 ${count} 条` }
      : { state: 'degraded', ruleCount: 0,
          message: '知识库 0 条 —— 种子数据没导入，链路会全程走 NO_EVIDENCE' }
  }
  catch (error) {
    health.value = { state: 'down', ruleCount: null, message: error.message }
  }
}

// 主题：默认跟随系统，可手动覆盖
const theme = ref(localStorage.getItem('da-theme') || 'system')
function applyTheme() {
  const root = document.documentElement
  if (theme.value === 'system') root.removeAttribute('data-theme')
  else root.setAttribute('data-theme', theme.value)
  localStorage.setItem('da-theme', theme.value)
}
function cycleTheme() {
  theme.value = { system: 'light', light: 'dark', dark: 'system' }[theme.value]
  applyTheme()
}

onMounted(() => { applyTheme(); checkHealth() })
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <div class="brand">
        <span class="mark" aria-hidden="true">◧</span>
        <div>
          <div class="name">临床研究数据处理 Agent</div>
        </div>
      </div>

      <nav>
        <RouterLink v-for="item in NAV" :key="item.to" :to="item.to"
                    :class="{ active: item.exact ? $route.path === '/' : $route.path.startsWith(item.to) }">
          {{ item.label }}
        </RouterLink>
      </nav>

      <div class="right">
        <button class="ghost health" :title="health.message" @click="checkHealth">
          <span class="dot" :class="health.state"></span>
          <span class="small">{{
            health.state === 'up' ? `后端在线 · ${health.ruleCount} 条规则`
            : health.state === 'degraded' ? '知识库为空'
            : health.state === 'down' ? '后端未连通' : '检查中…'
          }}</span>
        </button>
        <button class="ghost" @click="cycleTheme" :title="`主题：${theme}`">
          {{ theme === 'system' ? '◐' : theme === 'light' ? '☀' : '☾' }}
        </button>
      </div>
    </header>

    <main>
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.shell { min-height: 100%; display: flex; flex-direction: column; }

.topbar {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 12px 24px;
  background: var(--surface-1);
  border-bottom: 1px solid var(--border);
  position: sticky;
  top: 0;
  z-index: 10;
  flex-wrap: wrap;
}

.brand { display: flex; align-items: center; gap: 10px; }
.mark { font-size: 22px; color: var(--series-1); }
.name { font-weight: 600; font-size: 15px; }

nav { display: flex; gap: 4px; flex: 1 1 auto; }
nav a {
  padding: 6px 12px;
  border-radius: var(--radius-sm);
  color: var(--ink-2);
  font-weight: 500;
}
nav a:hover { background: var(--surface-2); text-decoration: none; }
nav a.active { background: var(--surface-2); color: var(--ink-1); }

.right { display: flex; gap: 8px; align-items: center; }
.health { display: inline-flex; align-items: center; gap: 7px; padding: 6px 10px; }

.dot { width: 8px; height: 8px; border-radius: 50%; flex: none; }
.dot.up { background: var(--status-good); }
.dot.degraded { background: var(--status-warning); }
.dot.down { background: var(--status-critical); }
.dot.checking { background: var(--ink-muted); }

main {
  flex: 1 1 auto;
  padding: 22px 24px 60px;
  max-width: 1280px;
  width: 100%;
  margin: 0 auto;
}
</style>
