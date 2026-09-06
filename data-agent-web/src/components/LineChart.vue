<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * 指标序列折线图。三条约定：null 断线不连 0；单一 y 轴，比率和延迟分成两张图不
 * 双轴；颜色绑在指标 key 上，不随排名变。
 */
const props = defineProps({
  /** [{ key, label, color, points: (number|null)[] }] */
  series: { type: Array, required: true },
  /** x 轴刻度文字，长度须与 points 一致 */
  xLabels: { type: Array, required: true },
  /** 每个点的补充说明，鼠标悬停时显示在 tooltip 顶部 */
  xNotes: { type: Array, default: () => [] },
  yMin: { type: Number, default: 0 },
  yMax: { type: Number, default: 1 },
  yTicks: { type: Number, default: 5 },
  format: { type: Function, default: (v) => (v * 100).toFixed(1) + '%' },
  height: { type: Number, default: 260 }
})

const wrap = ref(null)
const width = ref(720)
let observer = null

onMounted(() => {
  observer = new ResizeObserver(entries => {
    const w = entries[0].contentRect.width
    if (w > 0) width.value = w
  })
  observer.observe(wrap.value)
})
onBeforeUnmount(() => observer?.disconnect())

const PAD = { top: 14, right: 18, bottom: 30, left: 46 }

const plot = computed(() => ({
  w: Math.max(80, width.value - PAD.left - PAD.right),
  h: Math.max(60, props.height - PAD.top - PAD.bottom)
}))

const count = computed(() => props.xLabels.length)

function xAt(index) {
  if (count.value <= 1) return PAD.left + plot.value.w / 2
  return PAD.left + (index / (count.value - 1)) * plot.value.w
}
function yAt(value) {
  const t = (value - props.yMin) / (props.yMax - props.yMin || 1)
  return PAD.top + (1 - Math.min(1, Math.max(0, t))) * plot.value.h
}

/** null 处断线：把连续的非空段各自拼成一条 path */
function pathsOf(points) {
  const segments = []
  let current = []
  points.forEach((value, index) => {
    if (value === null || value === undefined) {
      if (current.length) segments.push(current)
      current = []
    }
    else current.push({ x: xAt(index), y: yAt(value) })
  })
  if (current.length) segments.push(current)
  return segments
    .filter(segment => segment.length > 1)
    .map(segment => segment.map((p, i) => `${i ? 'L' : 'M'}${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' '))
}

/** 孤立点（前后都是 null）也要画出来，否则它会整个消失 */
function lonePoints(points) {
  return points
    .map((value, index) => ({ value, index }))
    .filter(({ value, index }) =>
      value !== null && value !== undefined
      && (points[index - 1] === null || points[index - 1] === undefined)
      && (points[index + 1] === null || points[index + 1] === undefined))
    .map(({ value, index }) => ({ x: xAt(index), y: yAt(value) }))
}

const ticks = computed(() => {
  const out = []
  for (let i = 0; i <= props.yTicks; i++) {
    const value = props.yMin + (props.yMax - props.yMin) * (i / props.yTicks)
    out.push({ value, y: yAt(value) })
  }
  return out
})

// ── 悬停层 ─────────────────────────────────────────
const hover = ref(null)

function onMove(event) {
  const rect = event.currentTarget.getBoundingClientRect()
  const x = event.clientX - rect.left
  if (count.value === 0) return
  let nearest = 0
  let best = Infinity
  for (let i = 0; i < count.value; i++) {
    const distance = Math.abs(xAt(i) - x)
    if (distance < best) { best = distance; nearest = i }
  }
  hover.value = nearest
}

const tooltipStyle = computed(() => {
  if (hover.value === null) return {}
  const x = xAt(hover.value)
  const flip = x > PAD.left + plot.value.w * 0.6
  return {
    left: flip ? 'auto' : `${x + 14}px`,
    right: flip ? `${width.value - x + 14}px` : 'auto',
    top: `${PAD.top}px`
  }
})
</script>

<template>
  <div class="chart" ref="wrap">
    <svg :width="width" :height="height" role="img"
         @mousemove="onMove" @mouseleave="hover = null">
      <!-- 网格：只画水平线，且是发丝级 -->
      <g>
        <line v-for="tick in ticks" :key="'g' + tick.value"
              :x1="PAD.left" :x2="PAD.left + plot.w" :y1="tick.y" :y2="tick.y"
              stroke="var(--grid)" stroke-width="1" />
        <text v-for="tick in ticks" :key="'t' + tick.value"
              :x="PAD.left - 8" :y="tick.y + 4"
              text-anchor="end" font-size="11" fill="var(--ink-muted)"
              style="font-variant-numeric: tabular-nums">{{ format(tick.value) }}</text>
      </g>

      <!-- 基线 -->
      <line :x1="PAD.left" :x2="PAD.left + plot.w"
            :y1="PAD.top + plot.h" :y2="PAD.top + plot.h"
            stroke="var(--axis)" stroke-width="1" />

      <!-- x 轴刻度 -->
      <text v-for="(label, index) in xLabels" :key="'x' + index"
            :x="xAt(index)" :y="height - 10"
            text-anchor="middle" font-size="11" fill="var(--ink-muted)">{{ label }}</text>

      <!-- 悬停十字线 -->
      <line v-if="hover !== null"
            :x1="xAt(hover)" :x2="xAt(hover)" :y1="PAD.top" :y2="PAD.top + plot.h"
            stroke="var(--ink-muted)" stroke-width="1" stroke-dasharray="3 3" />

      <!-- 序列 -->
      <g v-for="line in series" :key="line.key">
        <path v-for="(d, index) in pathsOf(line.points)" :key="index"
              :d="d" fill="none" :stroke="line.color"
              stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
        <circle v-for="(point, index) in lonePoints(line.points)" :key="'lone' + index"
                :cx="point.x" :cy="point.y" r="4"
                :fill="line.color" stroke="var(--surface-1)" stroke-width="2" />
        <template v-if="hover !== null && line.points[hover] !== null && line.points[hover] !== undefined">
          <!-- 重叠标记之间留 2px 面色环 -->
          <circle :cx="xAt(hover)" :cy="yAt(line.points[hover])" r="4.5"
                  :fill="line.color" stroke="var(--surface-1)" stroke-width="2" />
        </template>
      </g>
    </svg>

    <div v-if="hover !== null" class="tooltip" :style="tooltipStyle">
      <div class="tip-head">
        {{ xLabels[hover] }}
        <span v-if="xNotes[hover]" class="tip-note">{{ xNotes[hover] }}</span>
      </div>
      <div v-for="line in series" :key="line.key" class="tip-row">
        <span class="swatch" :style="{ background: line.color }"></span>
        <span class="tip-label">{{ line.label }}</span>
        <span class="tip-value tnum">
          <template v-if="line.points[hover] === null || line.points[hover] === undefined">
            <em class="muted">未测</em>
          </template>
          <template v-else>{{ format(line.points[hover]) }}</template>
        </span>
      </div>
    </div>

    <!-- ≥2 条序列必须有图例：身份永远不能只靠颜色 -->
    <div v-if="series.length > 1" class="legend">
      <span v-for="line in series" :key="line.key" class="legend-item">
        <span class="swatch" :style="{ background: line.color }"></span>{{ line.label }}
      </span>
    </div>
  </div>
</template>

<style scoped>
.chart { position: relative; width: 100%; }
svg { display: block; width: 100%; }

.tooltip {
  position: absolute;
  background: var(--surface-1);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.14);
  padding: 8px 10px;
  font-size: 12px;
  pointer-events: none;
  min-width: 190px;
  z-index: 3;
}
.tip-head {
  font-weight: 600;
  margin-bottom: 6px;
  padding-bottom: 5px;
  border-bottom: 1px solid var(--grid);
}
.tip-note { display: block; font-weight: 400; color: var(--ink-muted); font-size: 11px; }
.tip-row { display: flex; align-items: center; gap: 6px; padding: 1px 0; }
.tip-label { flex: 1 1 auto; color: var(--ink-2); }
.tip-value { font-weight: 600; }

.legend {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  margin-top: 8px;
  padding-left: 46px;
  font-size: 12px;
  color: var(--ink-2);
}
.legend-item { display: inline-flex; align-items: center; gap: 6px; }
.swatch { width: 10px; height: 10px; border-radius: 3px; flex: none; }
</style>
