<script setup lang="ts">
import { ref } from 'vue'
const props = defineProps<{ content: string; streaming?: boolean }>()
const open = ref(false)
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
