<script setup lang="ts">
import { ref, watch } from 'vue'

const props = defineProps<{ content: string; streaming?: boolean }>()
const open = ref(false)

// streaming 翻转驱动自动开合：思考开始 → 展开（打字机可见）；
// 思考结束（正文开始/轮次结束，由父级 thinkingActive 判定）→ 自动合上。
// 中途的手动开合不被打断，直到下一次翻转。
watch(() => props.streaming, (s) => { open.value = s }, { immediate: true })
</script>

<template>
  <div class="thinking">
    <button class="toggle" @click="open = !open">
      <span class="dot" :class="{ live: streaming }"></span>
      {{ streaming ? '思考中' : '思考过程' }}
      <span class="chevron">{{ open ? '⌃' : '⌄' }}</span>
    </button>
    <pre v-show="open">{{ content }}</pre>
  </div>
</template>

<style scoped>
.thinking {
  position: relative;
}
.toggle {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  border: none;
  background: none;
  padding: 3px 0;
  font-size: 12.5px;
  color: var(--ink-soft);
  cursor: pointer;
  border-radius: var(--radius-sm);
}
.toggle:hover { color: var(--ink); }
.chevron { font-size: 11px; color: var(--ink-faint); }
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--line);
}
.dot.live {
  background: var(--pine);
  animation: dot-pulse 1.4s ease-in-out infinite;
}
@keyframes dot-pulse { 50% { opacity: 0.35; } }
pre {
  margin: 6px 0;
  padding: 8px 0 8px 14px;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.75;
  color: var(--ink-soft);
  white-space: pre-wrap;
  border-left: 2px solid var(--line);
}
@media (prefers-reduced-motion: reduce) {
  .dot.live { animation: none; }
}
</style>
