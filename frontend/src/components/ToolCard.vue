<script setup lang="ts">
import { ref } from 'vue'
const props = defineProps<{
  tool: { toolName: string; arguments?: string; result?: string; success?: boolean; durationMs?: number; callId: string }
}>()
const open = ref(false)
</script>

<template>
  <div class="tool-card" :class="{ failed: tool.success === false }">
    <button class="toggle" @click="open = !open">
      <span class="node"></span>
      <span class="name">{{ tool.toolName }}</span>
      <span v-if="tool.durationMs != null" class="meta">{{ tool.durationMs }}ms</span>
      <span v-if="tool.success === false" class="meta fail">失败</span>
      <span class="chevron">{{ open ? '⌃' : '⌄' }}</span>
    </button>
    <div v-show="open" class="detail">
      <pre v-if="tool.arguments">入参
{{ tool.arguments }}</pre>
      <pre v-if="tool.result">结果
{{ tool.result }}</pre>
    </div>
  </div>
</template>

<style scoped>
.tool-card { margin: 5px 0; }
.toggle {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  border: 1px solid var(--line);
  background: var(--surface);
  border-radius: var(--radius-md);
  padding: 4px 10px;
  font-size: 12px;
  cursor: pointer;
  max-width: 100%;
}
.toggle:hover { border-color: var(--ink-faint); }
.tool-card.failed .toggle { border-color: var(--danger); }
.node {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ink-soft);
  flex: none;
}
.tool-card.failed .node { background: var(--danger); }
.name {
  font-family: var(--font-mono);
  font-weight: 500;
  color: var(--ink);
}
.meta { color: var(--ink-faint); font-size: 11px; }
.meta.fail { color: var(--danger); font-weight: 500; }
.chevron { font-size: 10px; color: var(--ink-faint); }
.detail { margin-top: 4px; }
.detail pre {
  margin: 0 0 4px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  padding: 8px 12px;
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-soft);
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 320px;
  overflow-y: auto;
}
</style>
