<script setup lang="ts">
defineProps<{ role: 'user' | 'assistant'; content: string }>()
</script>

<template>
  <div class="bubble" :class="role">
    <div class="content">{{ content }}</div>
  </div>
</template>

<style scoped>
.bubble { display: flex; margin: 8px 0; }
.user { justify-content: flex-end; }
.assistant { justify-content: flex-start; }
.content {
  max-width: 80%; border-radius: 10px; padding: 8px 12px;
  white-space: pre-wrap; line-height: 1.6;
}
.user .content { background: #95ec69; }
.assistant .content { background: #f0f0f0; }
</style>
