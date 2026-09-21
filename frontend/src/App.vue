<script setup lang="ts">
import { onMounted, ref, reactive } from 'vue'
import { streamSse } from './api/sse'
import { createConversation, getTurns, listConversations } from './api/rest'
import { applySseEvent, failTurn, newTurn, thinkingActive, turnFromRecord } from './turn'
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
  // reactive 包裹：流式增量直接驱动重渲染（raw 对象的修改不触发响应式，
  // 会导致整轮内容等流结束才一次性出现）
  const turn: ChatTurn = reactive(newTurn(text))
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

const fillPrompt = (text: string) => {
  input.value = text
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
        <div v-if="turns.length === 0" class="welcome">
          <h2>你好，我是你的个人小助手 🤖</h2>
          <p>我可以陪你聊天、答疑、写文案，也能在需要时动用工具干活：</p>
          <ul>
            <li>📂 读写工作区文件、列目录</li>
            <li>💻 执行 Shell 命令</li>
            <li>🧠 按需加载技能（如 CSV 分析、Markdown 报告）</li>
          </ul>
          <p class="try">试试：</p>
          <div class="prompts">
            <button v-for="p in ['用一句话介绍你自己', '列出工作区里有什么文件', '写一段五十字的产品介绍']"
                    :key="p" @click="fillPrompt(p)">{{ p }}</button>
          </div>
        </div>
        <div v-for="(turn, i) in turns" :key="i" class="turn">
          <MessageBubble role="user" :content="turn.userText" v-if="turn.userText" />
          <ThinkingBlock :content="turn.thinking" :streaming="thinkingActive(turn)" v-if="turn.thinking" />
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
.welcome { padding: 48px 32px; color: #333; }
.welcome h2 { font-size: 20px; margin-bottom: 8px; }
.welcome ul { margin: 8px 0 16px; padding-left: 20px; line-height: 1.9; color: #555; }
.welcome .try { font-size: 13px; color: #888; margin-bottom: 6px; }
.prompts { display: flex; flex-wrap: wrap; gap: 8px; }
.prompts button {
  border: 1px solid #d0e0f0; background: #f5f9ff; color: #1a73e8;
  border-radius: 16px; padding: 6px 14px; font-size: 13px; cursor: pointer;
}
.prompts button:hover { background: #e8f0fe; }
</style>
