<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { streamSse } from './api/sse'
import { createConversation, getTurns, listConversations } from './api/rest'
import { applySseEvent, failTurn, newTurn, turnFromRecord } from './turn'
import type { ChatTurn, TurnRecord } from './types'
import ThinkingBlock from './components/ThinkingBlock.vue'
import ToolCard from './components/ToolCard.vue'
import MessageBubble from './components/MessageBubble.vue'

interface ConversationItem { id: number; title: string; turnCount: number; updatedAt: string }

const conversations = ref<ConversationItem[]>([])
const activeId = ref<number | null>(null)
const input = ref('')
const sending = ref(false)
const turns = ref<ChatTurn[]>([])

const newChat = async () => {
  const c = await createConversation()
  await refresh()
  await select(c.id)
}

const refresh = async () => {
  conversations.value = await listConversations()
}

const select = async (id: number) => {
  activeId.value = id
  turns.value = []
  const history: TurnRecord[] = await getTurns(id)
  // 回放与实时流式共用 turn.ts 的分区/合并/错误渲染规则
  turns.value = history.map(turnFromRecord)
}

const send = async () => {
  if (!input.value.trim() || !activeId.value || sending.value) return
  const text = input.value
  input.value = ''
  sending.value = true
  const turn: ChatTurn = newTurn(text)
  turns.value.push(turn)
  try {
    await streamSse(`/api/conversations/${activeId.value}/chat`, { content: text }, (ev) => {
      applySseEvent(turn, ev)
    })
    if (turn.status === 'streaming') turn.status = 'done'
    refresh()
  } catch (err) {
    // 网络失败（fetch reject / HTTP 非 2xx）：收口为 error 并展示错误文本，
    // 轮次不再卡在 streaming
    failTurn(turn, err)
  } finally {
    sending.value = false
  }
}

onMounted(async () => {
  await refresh()
  if (conversations.value.length === 0) await newChat()
  else await select(conversations.value[0].id)
})
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <button class="new" @click="newChat">＋ 新会话</button>
      <div v-for="c in conversations" :key="c.id"
           class="conv" :class="{ active: c.id === activeId }" @click="select(c.id)">
        {{ c.title }} <small>{{ c.turnCount }} 轮</small>
      </div>
    </aside>
    <main class="chat">
      <div class="timeline">
        <div v-for="(turn, i) in turns" :key="i" class="turn">
          <MessageBubble role="user" :content="turn.userText" v-if="turn.userText" />
          <ThinkingBlock :content="turn.thinking" :streaming="turn.status === 'streaming'" v-if="turn.thinking" />
          <ToolCard v-for="t in turn.tools" :key="t.callId" :tool="t" />
          <MessageBubble role="assistant" :content="turn.text" v-if="turn.text" />
          <p class="error-line" v-if="turn.errorText">错误：{{ turn.errorText }}</p>
        </div>
      </div>
      <div class="composer">
        <textarea v-model="input" @keydown.enter.exact.prevent="send"
                  placeholder="输入消息，Enter 发送" :disabled="sending" />
        <button @click="send" :disabled="sending || !activeId">发送</button>
      </div>
    </main>
  </div>
</template>

<style scoped>
.layout { display: flex; height: 100vh; }
.sidebar { width: 220px; border-right: 1px solid #eee; padding: 12px; overflow-y: auto; }
.new { width: 100%; padding: 8px; border-radius: 8px; border: none; background: #1a73e8; color: #fff; cursor: pointer; }
.conv { padding: 8px; border-radius: 8px; cursor: pointer; margin-top: 4px; font-size: 14px; }
.conv.active { background: #e8f0fe; }
.chat { flex: 1; display: flex; flex-direction: column; }
.timeline { flex: 1; overflow-y: auto; padding: 16px; }
.composer { display: flex; gap: 8px; padding: 12px; border-top: 1px solid #eee; }
.composer textarea { flex: 1; resize: none; height: 60px; border-radius: 8px; border: 1px solid #ccc; padding: 8px; }
.error-line { color: #d93025; font-size: 13px; margin: 4px 0; }
</style>
