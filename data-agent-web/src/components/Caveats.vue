<script setup>
/** caveats 一律置顶，不折叠、不放页尾、不做小灰字：降级要出现在结果里。 */
defineProps({
  items: { type: Array, default: () => [] },
  title: { type: String, default: '本次测不准的东西' }
})
</script>

<template>
  <div v-if="items && items.length" class="caveats" role="note">
    <div class="head">
      <span aria-hidden="true">⚑</span>
      <strong>{{ title }}</strong>
      <span class="muted small">先读这一段：把「没测」当成「测了且通过」是指标表最危险的读法</span>
    </div>
    <ul>
      <li v-for="(item, index) in items" :key="index">{{ item }}</li>
    </ul>
  </div>
</template>

<style scoped>
.caveats {
  border: 1px solid var(--status-warning);
  border-left-width: 4px;
  border-radius: var(--radius-sm);
  background: var(--surface-1);
  padding: 12px 16px;
}
.head { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }
.head > span[aria-hidden] { color: var(--status-warning); }
ul { margin: 8px 0 0; padding-left: 24px; }
li { color: var(--ink-2); }
</style>
