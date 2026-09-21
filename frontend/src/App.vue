<script setup lang="ts">
import { nextTick, onMounted, ref, reactive, watch } from 'vue'
import { streamSse } from './api/sse'
import { createConversation, fetchIdentityOptions, getTurns, listConversations } from './api/rest'
import type { TenantIdentityOptions } from './api/rest'
import { ApiError } from './api/error'
import { currentIdentity, setIdentity } from './api/identity'
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
const sidebarOpen = ref(false)
const timelineEl = ref<HTMLElement | null>(null)

const globalError = ref('')

/** 会话不可用的统一文案（turn 收尾与全局横幅共用，改文案只动这里） */
const CONV_UNAVAILABLE = '该会话已不可用（可能已删除或归属其他用户）'

// 身份切换器：候选来自 /api/identity/options（免鉴权）；拉取失败隐藏切换器不影响使用
const identityOptions = ref<TenantIdentityOptions[]>([])
const identityKey = ref(`${currentIdentity().tenantId}/${currentIdentity().userId}`)

// 401：身份无效——切换身份或检查 env 配置（无登录可跳）
const authErrorMessage = () => {
  const cur = currentIdentity()
  return `当前身份（${cur.tenantId}/${cur.userId}）未注册或已失效，请切换身份或检查 VITE_TENANT_ID / VITE_USER_ID 配置`
}

// 401 统一收口：命中时置全局横幅并返回 true，调用方按需 rethrow/收尾轮次
const authFailed = (err: unknown): boolean => {
  if (err instanceof ApiError && err.unauthorized) {
    globalError.value = authErrorMessage()
    return true
  }
  return false
}

// 切换身份：写记忆、清视图状态、按新身份重拉会话列表
const switchIdentity = async (key: string) => {
  const [tenantId, userId] = key.split('/')
  if (!tenantId || !userId || key === identityKey.value) return
  setIdentity(tenantId, userId)
  identityKey.value = key
  conversations.value = []
  activeId.value = null
  turns.value = []
  globalError.value = ''
  await refresh()
}

// 会话不可用：从列表移除并回到欢迎态（activeId 置空）
const dropConversation = (id: number) => {
  conversations.value = conversations.value.filter((c) => c.id !== id)
  if (activeId.value === id) {
    activeId.value = null
    turns.value = []
  }
}

const newChat = async () => {
  try {
    const c = await createConversation()
    await refresh()
    await select(c.id)
  } catch (err) {
    if (!authFailed(err)) throw err
  }
}

const refresh = async () => {
  try {
    conversations.value = await listConversations()
  } catch (err) {
    if (!authFailed(err)) throw err
  }
}

const select = async (id: number) => {
  activeId.value = id
  sidebarOpen.value = false
  turns.value = []
  try {
    const history: TurnRecord[] = await getTurns(id)
    // 回放与实时流式共用 turn.ts 的分区/合并/错误渲染规则
    turns.value = history.map(turnFromRecord)
    await scrollToBottom(true)
  } catch (err) {
    if (err instanceof ApiError && err.notFound) {
      globalError.value = '该会话不存在或无权访问，已自动移除'
      dropConversation(id)
    } else if (!authFailed(err)) {
      throw err
    }
  }
}

const send = async () => {
  if (!input.value.trim() || !activeId.value || sending.value) return
  const text = input.value
  // 捕获发送时刻的会话 id：流式期间用户可能切换会话，
  // 错误分支（URL/drop）必须作用于发起会话而非重读当前 activeId
  const convId = activeId.value
  input.value = ''
  sending.value = true
  // reactive 包裹：流式增量直接驱动重渲染（raw 对象的修改不触发响应式，
  // 会导致整轮内容等流结束才一次性出现）
  const turn: ChatTurn = reactive(newTurn(text))
  turns.value.push(turn)
  try {
    await streamSse(`/api/conversations/${convId}/chat`, { content: text }, (ev) => {
      applySseEvent(turn, ev)
    })
    if (turn.status === 'streaming') turn.status = 'done'
    refresh()
  } catch (err) {
    // 网络失败（fetch reject / HTTP 非 2xx）：收口为 error 并展示错误文本，
    // 轮次不再卡在 streaming；404/401 走无感分支
    if (err instanceof ApiError && err.notFound) {
      failTurn(turn, new Error(CONV_UNAVAILABLE))
      globalError.value = CONV_UNAVAILABLE
      dropConversation(convId)
      await refresh()
    } else {
      authFailed(err) // 401 时置全局横幅（failTurn 对 401 与其他错误一致）
      failTurn(turn, err)
    }
  } finally {
    sending.value = false
  }
}

/* 流式期间自动跟随滚动（用户主动上翻超过一屏则不打扰） */
const scrollToBottom = async (force = false) => {
  await nextTick()
  const el = timelineEl.value
  if (!el) return
  const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 120
  if (force || nearBottom) el.scrollTop = el.scrollHeight
}
watch(
  () => turns.value.map(t => t.thinking.length + t.text.length + t.tools.length + t.status).join(','),
  () => { if (sending.value) scrollToBottom() },
)

const fillPrompt = (text: string) => {
  input.value = text
}

onMounted(async () => {
  // 身份候选与会话列表互不依赖，并行拉取（options 失败仅隐藏切换器，不阻塞启动）
  fetchIdentityOptions()
    .then((opts) => { identityOptions.value = opts })
    .catch(() => { identityOptions.value = [] })
  await refresh()
  if (conversations.value.length === 0) await newChat()
  else await select(conversations.value[0].id)
})
</script>

<template>
  <div class="layout">
    <button class="menu-btn" @click="sidebarOpen = !sidebarOpen" aria-label="会话列表">☰</button>

    <aside class="sidebar" :class="{ open: sidebarOpen }" @click.self="sidebarOpen = false">
      <div class="brand">
        <span class="mark"></span>
        <span class="name">LinAgent</span>
      </div>
      <button class="new" @click="newChat">新对话</button>
      <div class="conv-list">
        <div v-for="c in conversations" :key="c.id"
             class="conv" :class="{ active: c.id === activeId }" @click="select(c.id)">
          <span class="conv-title">{{ c.title }}</span>
          <small class="conv-count">{{ c.turnCount }}</small>
        </div>
      </div>
    </aside>

    <main class="chat">
      <!-- 身份切换器：右上角常驻（fixed，与移动端 ☰ 对角），下拉弹层不再遮挡侧栏按钮 -->
      <select v-if="identityOptions.length" class="identity" :value="identityKey"
              aria-label="切换身份"
              @change="switchIdentity(($event.target as HTMLSelectElement).value)">
        <optgroup v-for="t in identityOptions" :key="t.tenantId" :label="t.tenantId">
          <option v-for="u in t.users" :key="u.userId" :value="`${t.tenantId}/${u.userId}`">
            {{ u.name }}（{{ u.userId }}）
          </option>
        </optgroup>
      </select>
      <div class="global-error" v-if="globalError" @click="globalError = ''">
        {{ globalError }}（点击关闭）
      </div>
      <div class="timeline" ref="timelineEl">
        <!-- 空会话欢迎面板 -->
        <div v-if="turns.length === 0" class="welcome">
          <h1 class="wordmark">LinAgent</h1>
          <p class="tagline">你的个人小助手，在你本机运行。</p>
          <ul class="caps">
            <li>聊天答疑 · 写作与总结</li>
            <li>读写工作区文件 · 执行 Shell 命令</li>
            <li>按需加载技能（CSV 分析、Markdown 报告）</li>
          </ul>
          <p class="try-hint">试着问我——</p>
          <div class="prompts">
            <button v-for="p in ['列出工作区里有什么文件', '写一段五十字的产品介绍', '用 csv-analysis 技能分析 data.csv']"
                    :key="p" @click="fillPrompt(p)">{{ p }}</button>
          </div>
        </div>

        <!-- 每轮 = 一条作业轨道：用户气泡 → (思考/工具…沿轨线) → 答案 -->
        <article v-for="(turn, i) in turns" :key="i" class="turn">
          <MessageBubble role="user" :content="turn.userText" v-if="turn.userText" />
          <div class="rail" v-if="turn.thinking || turn.tools.length || turn.text || turn.errorText">
            <ThinkingBlock :content="turn.thinking" :streaming="thinkingActive(turn)" v-if="turn.thinking" />
            <ToolCard v-for="t in turn.tools" :key="t.callId" :tool="t" />
            <MessageBubble role="assistant" :content="turn.text"
                           :streaming="turn.status === 'streaming' && !!turn.text" v-if="turn.text" />
            <p class="error-line" v-if="turn.errorText">{{ turn.errorText }}</p>
          </div>
        </article>
      </div>

      <div class="composer">
        <textarea v-model="input" @keydown.enter.exact.prevent="send"
                  placeholder="输入消息，Enter 发送" :disabled="sending" rows="2" />
        <button class="send" @click="send" :disabled="sending || !activeId">发送</button>
      </div>
    </main>
  </div>
</template>

<style scoped>
.layout { display: flex; height: 100vh; }

/* ============ 侧栏 ============ */
.sidebar {
  width: 248px;
  flex: none;
  display: flex;
  flex-direction: column;
  padding: 20px 14px;
  background: var(--paper);
  border-right: 1px solid var(--line);
}
.brand {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 0 8px 18px;
}
.mark {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  background: var(--pine);
  flex: none;
}
.brand .name {
  font-family: var(--font-display);
  font-weight: 700;
  font-size: 19px;
  letter-spacing: -0.02em;
  color: var(--ink);
}
.identity {
  position: fixed;
  top: 14px;
  right: 14px;
  z-index: 30;
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--ink-soft);
  border-radius: var(--radius-sm);
  height: 36px;
  padding: 0 10px;
  font-size: 13px;
  cursor: pointer;
  max-width: 40vw;
}

.new {
  border: 1px solid var(--pine);
  background: transparent;
  color: var(--pine);
  border-radius: var(--radius-md);
  padding: 8px 0;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  margin-bottom: 14px;
}
.new:hover { background: var(--pine-soft); }
.new:disabled { opacity: 0.5; cursor: default; }
.conv-list { overflow-y: auto; flex: 1; }
.conv {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border-radius: var(--radius-md);
  cursor: pointer;
  margin-bottom: 2px;
}
.conv:hover { background: rgba(46, 90, 71, 0.06); }
.conv.active { background: var(--pine-soft); }
.conv-title {
  font-size: 13.5px;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.conv-count { font-size: 11px; color: var(--ink-faint); flex: none; }

/* ============ 主区 ============ */
.chat {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.timeline {
  flex: 1;
  overflow-y: auto;
  padding: 28px 24px 12px;
}
.turn, .welcome {
  max-width: var(--content-width);
  margin: 0 auto;
}

/* 作业轨道：左轨线串起思考/工具/答案（编码轮内时序） */
.rail {
  position: relative;
  margin: 8px 0 26px;
  padding-left: 26px;
}
.rail::before {
  content: "";
  position: absolute;
  left: 10px;
  top: 10px;
  bottom: 8px;
  width: 2px;
  background: var(--line);
  border-radius: 1px;
}
.rail > * { position: relative; }

.error-line {
  color: var(--danger);
  background: var(--danger-soft);
  border-radius: var(--radius-sm);
  padding: 7px 12px;
  font-size: 13px;
  margin: 6px 0;
}

.global-error {
  margin: 0 16px 8px;
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--danger-soft);
  color: var(--danger);
  font-size: 13px;
  cursor: pointer;
}

/* ============ 欢迎面板 ============ */
.welcome { padding: 10vh 8px 0; }
.wordmark {
  font-family: var(--font-display);
  font-weight: 700;
  font-size: 52px;
  letter-spacing: -0.03em;
  margin: 0 0 10px;
  color: var(--ink);
}
.wordmark::after {
  content: "";
  display: inline-block;
  width: 11px;
  height: 11px;
  border-radius: 3px;
  background: var(--pine);
  margin-left: 8px;
  vertical-align: 6px;
}
.tagline { font-size: 17px; color: var(--ink-soft); margin: 0 0 26px; }
.caps {
  list-style: none;
  padding: 0;
  margin: 0 0 34px;
  color: var(--ink-soft);
  font-size: 14px;
}
.caps li {
  padding: 5px 0 5px 18px;
  position: relative;
}
.caps li::before {
  content: "";
  position: absolute;
  left: 2px;
  top: 0.95em;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--pine);
  opacity: 0.65;
}
.try-hint { font-size: 13px; color: var(--ink-faint); margin: 0 0 10px; }
.prompts { display: flex; flex-wrap: wrap; gap: 10px; }
.prompts button {
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--ink-soft);
  border-radius: 999px;
  padding: 8px 16px;
  font-size: 13.5px;
  cursor: pointer;
}
.prompts button:hover {
  border-color: var(--pine);
  color: var(--pine);
}

/* ============ 输入区 ============ */
.composer {
  display: flex;
  gap: 10px;
  padding: 14px 24px 20px;
  max-width: calc(var(--content-width) + 48px);
  width: 100%;
  margin: 0 auto;
}
.composer textarea {
  flex: 1;
  resize: none;
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  padding: 11px 16px;
  font-family: inherit;
  font-size: 15px;
  line-height: 1.6;
  color: var(--ink);
}
.composer textarea:focus-visible {
  outline: none;
  border-color: var(--pine);
  box-shadow: 0 0 0 2px var(--pine-soft);
}
.send {
  align-self: flex-end;
  border: none;
  background: var(--pine);
  color: #fff;
  border-radius: var(--radius-md);
  padding: 12px 22px;
  font-size: 14.5px;
  font-weight: 500;
  cursor: pointer;
}
.send:hover { background: var(--pine-deep); }
.send:disabled { opacity: 0.45; cursor: default; }

/* ============ 移动端 ============ */
.menu-btn {
  display: none;
  position: fixed;
  top: 14px;
  left: 14px;
  z-index: 30;
  border: 1px solid var(--line);
  background: var(--surface);
  border-radius: var(--radius-sm);
  width: 36px;
  height: 36px;
  font-size: 15px;
  cursor: pointer;
}
@media (max-width: 768px) {
  .menu-btn { display: block; }
  .sidebar {
    position: fixed;
    inset: 0 auto 0 0;
    z-index: 20;
    transform: translateX(-100%);
    transition: transform 0.2s ease;
    box-shadow: 0 0 40px rgba(30, 36, 34, 0.15);
    background: var(--paper);
  }
  .sidebar.open { transform: none; }
  .timeline { padding: 64px 16px 8px; }
  .composer { padding: 10px 16px 16px; }
  .wordmark { font-size: 40px; }
  .rail { padding-left: 20px; }
}
</style>
