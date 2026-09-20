<script setup lang="ts">
import { ref } from 'vue'
const props = defineProps<{ tool: { toolName: string; arguments?: string; result?: string; success?: boolean; durationMs?: number; callId: string } }>()
const open = ref(false)
</script>

<template>
  <div class="tool-card" :class="{ failed: tool.success === false }">
    <button class="toggle" @click="open = !open">
      🔧 {{ tool.toolName }}
      <span v-if="tool.durationMs"> · {{ tool.durationMs }}ms</span>
      <span v-if="tool.success === false"> · 失败</span>
    </button>
    <div v-show="open" class="detail">
      <pre v-if="tool.arguments">入参: {{ tool.arguments }}</pre>
      <pre v-if="tool.result">结果: {{ tool.result }}</pre>
    </div>
  </div>
</template>

<style scoped>
.tool-card { margin: 4px 0; }
.tool-card.failed .toggle { color: #c00; }
.toggle {
  border: 1px solid #d0e0f0; background: #f5f9ff; border-radius: 6px;
  padding: 2px 10px; font-size: 12px; cursor: pointer;
}
.detail pre {
  background: #fafafa; border-radius: 6px; padding: 6px;
  font-size: 12px; white-space: pre-wrap;
}
</style>
