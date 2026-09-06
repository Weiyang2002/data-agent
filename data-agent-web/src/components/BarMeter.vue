<script setup>
import { computed } from 'vue'

/**
 * 横向条：编码「量」，用单一色相（顺序色）而非分类色。细条、数据端 4px 圆角锚在
 * 基线上、条间 2px 间隙、数值直接标在条右侧。
 */
const props = defineProps({
  rows: { type: Array, required: true },   // { label, value, display, sub }
  max: { type: Number, default: null },
  /** 强调色，默认顺序色的中段 */
  color: { type: String, default: 'var(--seq-450)' },
  labelWidth: { type: String, default: '160px' }
})

const upper = computed(() => {
  if (props.max !== null) return props.max
  const values = props.rows.map(r => Number(r.value) || 0)
  return Math.max(...values, 1)
})

function pct(value) {
  const v = Number(value) || 0
  return upper.value > 0 ? Math.max(0, (v / upper.value) * 100) : 0
}
</script>

<template>
  <div class="meter">
    <div v-for="row in rows" :key="row.label" class="bar-row">
      <div class="label" :style="{ width: labelWidth }" :title="row.label">
        {{ row.label }}
        <span v-if="row.sub" class="sub">{{ row.sub }}</span>
      </div>
      <div class="track">
        <div class="fill" :style="{ width: pct(row.value) + '%', background: row.color || color }"></div>
      </div>
      <div class="value tnum">{{ row.display }}</div>
    </div>
  </div>
</template>

<style scoped>
.meter { display: flex; flex-direction: column; gap: 2px; }
.bar-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 3px 0;
}
.label {
  flex: none;
  font-size: 12px;
  color: var(--ink-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.label .sub { color: var(--ink-muted); margin-left: 6px; }
.track {
  flex: 1 1 auto;
  min-width: 40px;
  height: 12px;
  background: var(--surface-2);
  border-radius: 2px 4px 4px 2px;
}
.fill {
  height: 100%;
  /* 数据端圆角，锚定基线端保持方角 */
  border-radius: 2px 4px 4px 2px;
  min-width: 2px;
  transition: width 180ms ease-out;
}
.value {
  flex: none;
  min-width: 92px;
  text-align: right;
  font-size: 12px;
  color: var(--ink-1);
  font-weight: 600;
}
</style>
