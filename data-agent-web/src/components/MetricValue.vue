<script setup>
/**
 * 把 null 和 0 显示成两样东西：null（本轮没有样本）显示为「未测」，0 正常渲染为
 * "0.00%"。后端在相关 VO 上用 `@JsonInclude(ALWAYS)` 保证 null 字段不消失。
 */
const props = defineProps({
  value: { type: Number, default: null },
  /** ratio 按百分比显示；int / ms 原样显示 */
  format: { type: String, default: 'ratio' },
  numerator: { type: Number, default: null },
  denominator: { type: Number, default: null },
  /** 未测时的说明，鼠标悬停可见 */
  unmeasuredHint: { type: String, default: '本轮没有该指标的样本（分母为 0），不是得了 0 分' }
})

function formatted() {
  const v = props.value
  if (props.format === 'ratio') return (v * 100).toFixed(2) + '%'
  if (props.format === 'ms') return v >= 1000 ? (v / 1000).toFixed(2) + 's' : v + 'ms'
  if (props.format === 'int') return v.toLocaleString('zh-CN')
  return String(v)
}
</script>

<template>
  <span v-if="value === null || value === undefined"
        class="unmeasured" :title="unmeasuredHint">未测</span>
  <span v-else class="value tnum">
    {{ formatted() }}
    <span v-if="denominator !== null" class="frac">
      {{ numerator }}/{{ denominator }}
    </span>
  </span>
</template>

<style scoped>
.unmeasured {
  color: var(--ink-muted);
  font-style: italic;
  border-bottom: 1px dotted var(--ink-muted);
  cursor: help;
}
.value { font-weight: 600; }
.frac {
  font-weight: 400;
  color: var(--ink-muted);
  font-size: 12px;
  margin-left: 4px;
}
</style>
