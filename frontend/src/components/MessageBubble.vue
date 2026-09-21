<script setup lang="ts">
import { computed } from 'vue'
import { renderAnswer } from '../api/markdown'

const props = defineProps<{ role: 'user' | 'assistant'; content: string; streaming?: boolean }>()

// 用户 = 唯一的气泡（松绿）；助手 = 文档正文（Markdown 渲染，非镜像气泡）
const answerHtml = computed(() =>
  props.role === 'assistant' ? renderAnswer(props.content) : ''
)
</script>

<template>
  <div v-if="role === 'user'" class="user-row">
    <div class="user-bubble">{{ content }}</div>
  </div>
  <div v-else class="answer-doc" :class="{ caret: streaming }" v-html="answerHtml"></div>
</template>

<style scoped>
.user-row {
  display: flex;
  justify-content: flex-end;
  margin: 18px 0 6px;
}
.user-bubble {
  max-width: min(78%, 520px);
  background: var(--pine);
  color: #fff;
  padding: 9px 14px;
  border-radius: var(--radius-lg);
  border-bottom-right-radius: 4px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
