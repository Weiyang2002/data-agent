<script setup>
import { computed } from 'vue'

/**
 * 状态徽标。状态色永远配图标 + 文字，不靠颜色单独承载语义
 * （dataviz 的硬规则，也正好对上本项目的可访问性需求）。
 */
const props = defineProps({
  kind: { type: String, required: true },
  text: { type: String, default: '' }
})

const MAP = {
  // 知识库三态 —— 本项目的招牌
  RESOLVED:    { color: 'var(--status-good)',     icon: '✓', label: 'RESOLVED' },
  AMBIGUOUS:   { color: 'var(--status-warning)',  icon: '⚑', label: 'AMBIGUOUS' },
  NO_EVIDENCE: { color: 'var(--status-serious)',  icon: '∅', label: 'NO_EVIDENCE' },

  // 任务状态
  DONE:       { color: 'var(--status-good)',     icon: '✓', label: 'DONE' },
  CLARIFYING: { color: 'var(--status-warning)',  icon: '?', label: 'CLARIFYING' },
  FAILED:     { color: 'var(--status-critical)', icon: '✕', label: 'FAILED' },

  // 阶段状态
  SUCCESS: { color: 'var(--status-good)',     icon: '✓', label: 'SUCCESS' },
  SKIPPED: { color: 'var(--ink-muted)',       icon: '–', label: 'SKIPPED' },

  // 校验发现的严重度
  HIGH:   { color: 'var(--status-critical)', icon: '▲', label: 'HIGH' },
  MEDIUM: { color: 'var(--status-warning)',  icon: '●', label: 'MEDIUM' },
  LOW:    { color: 'var(--ink-muted)',       icon: '○', label: 'LOW' },

  // 通用
  PASS: { color: 'var(--status-good)',     icon: '✓', label: '通过' },
  FAIL: { color: 'var(--status-critical)', icon: '✕', label: '未通过' },
  YES:  { color: 'var(--status-good)',     icon: '✓', label: '是' },
  NO:   { color: 'var(--ink-muted)',       icon: '–', label: '否' }
}

const spec = computed(() => MAP[props.kind] ?? {
  color: 'var(--ink-muted)', icon: '·', label: props.kind
})
</script>

<template>
  <span class="badge" :style="{ color: spec.color, borderColor: spec.color }">
    <span aria-hidden="true">{{ spec.icon }}</span>{{ text || spec.label }}
  </span>
</template>

<style scoped>
.badge { background: transparent; }
</style>
