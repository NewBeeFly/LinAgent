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
      {{ streaming ? '💭 思考中…' : '💭 思考过程' }} {{ open ? '▾' : '▸' }}
    </button>
    <pre v-show="open">{{ content }}</pre>
  </div>
</template>

<style scoped>
.thinking { margin: 4px 0; }
.toggle {
  border: none; background: #f0f0f0; border-radius: 6px;
  padding: 2px 10px; font-size: 12px; color: #666; cursor: pointer;
}
pre {
  background: #fafafa; border: 1px dashed #ddd; border-radius: 6px;
  padding: 8px; font-size: 12px; white-space: pre-wrap; margin: 4px 0;
}
</style>
