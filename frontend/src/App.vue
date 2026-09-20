<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { streamSse } from './api/sse'
import { createConversation, getTurns, listConversations } from './api/rest'
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
  for (const t of history) {
    const turn: ChatTurn = { userText: '', thinking: '', text: '', tools: [], status: 'done' }
    for (const m of t.messages) {
      if (m.msgType === 'USER') turn.userText = m.content ?? ''
      else if (m.msgType === 'THINKING') turn.thinking += (m.content ?? '') + '\n'
      else if (m.msgType === 'TEXT') turn.text += m.content ?? ''
      else if (m.msgType === 'TOOL_CALL') turn.tools.push({ callId: m.callId ?? '', toolName: m.toolName ?? '', arguments: m.arguments })
      else if (m.msgType === 'TOOL_RESULT') {
        const target = turn.tools.find(x => x.callId === m.callId)
        if (target) Object.assign(target, { result: m.result, success: m.success, durationMs: m.durationMs })
      }
    }
    turns.value.push(turn)
  }
}

const send = async () => {
  if (!input.value.trim() || !activeId.value || sending.value) return
  const text = input.value
  input.value = ''
  sending.value = true
  const turn: ChatTurn = { userText: text, thinking: '', text: '', tools: [], status: 'streaming' }
  turns.value.push(turn)
  try {
    await streamSse(`/api/conversations/${activeId.value}/chat`, { content: text }, (ev) => {
      if (ev.event === 'thinking_delta') turn.thinking += ev.data.content
      else if (ev.event === 'message_delta') turn.text += ev.data.content
      else if (ev.event === 'tool_call') turn.tools.push({ callId: ev.data.callId, toolName: ev.data.toolName, arguments: ev.data.arguments })
      else if (ev.event === 'tool_result') {
        const target = turn.tools.find(x => x.callId === ev.data.callId)
        if (target) Object.assign(target, ev.data)
      } else if (ev.event === 'turn_done') turn.status = 'done'
      else if (ev.event === 'error') { turn.status = 'error'; console.error(ev.data) }
    })
    if (turn.status === 'streaming') turn.status = 'done'
    refresh()
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
</style>
