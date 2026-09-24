<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, reactive, watch, computed } from 'vue'
import { streamSse } from './api/sse'
import { createConversation, fetchIdentityOptions, getPendingApproval, getTurns, listConversations, putConversationMode } from './api/rest'
import type { ConversationSummary, TenantIdentityOptions } from './api/rest'
import { ApiError } from './api/error'
import { currentIdentity, setIdentity } from './api/identity'
import { applySseEvent, executedWithoutText, failTurn, newTurn, thinkingActive, turnFromRecord } from './turn'
import { CHAT_MODES, isChatOnly, modeMeta } from './modes'
import type { ApprovalDecisionPayload, ChatTurn, TurnRecord } from './types'
import ThinkingBlock from './components/ThinkingBlock.vue'
import ToolCard from './components/ToolCard.vue'
import MessageBubble from './components/MessageBubble.vue'
import ApprovalCard from './components/ApprovalCard.vue'
import PermissionSettings from './views/PermissionSettings.vue'

// 轻量哈希路由（无 vue-router 依赖，单设置页不值得引入）：#/settings/permissions ↔ 权限设置
const SETTINGS_HASH = '#/settings/permissions'
const route = ref(window.location.hash === SETTINGS_HASH ? 'settings' : 'chat')
const syncRoute = () => {
  route.value = window.location.hash === SETTINGS_HASH ? 'settings' : 'chat'
}
const openSettings = () => {
  window.location.hash = SETTINGS_HASH
}

const conversations = ref<ConversationSummary[]>([])
const activeId = ref<number | null>(null)
const input = ref('')
const sending = ref(false)
const turns = ref<ChatTurn[]>([])
const sidebarOpen = ref(false)
const timelineEl = ref<HTMLElement | null>(null)

const globalError = ref('')

/** 审批挂起横幅（409 挡回 / 刷新恢复发现挂起轮时置位，决议完成自动消解） */
const approvalPending = ref(false)

/** 会话不可用的统一文案（turn 收尾与全局横幅共用，改文案只动这里） */
const CONV_UNAVAILABLE = '该会话已不可用（可能已删除或归属其他用户）'

/** 会话不存在的统一文案（select / 切档的 404 分支共用） */
const CONV_NOT_FOUND = '该会话不存在或无权访问，已自动移除'

/** 切档被待审批轮挡回（PUT /mode 409；body 与审批 409 同形，按挂掉的接口区分语义） */
const MODE_SWITCH_BLOCKED = '存在待审批操作，请先完成审批再切换模式'

/** 纯聊档输入框占位（该档无工具，明确预期避免用户要求文件/shell 操作） */
const PLACEHOLDER_CHAT_ONLY = '纯对话模式（无工具）'

/** 默认输入框占位 */
const PLACEHOLDER_DEFAULT = '输入消息，Enter 发送'

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
  approvalPending.value = false
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
  approvalPending.value = false
  try {
    const history: TurnRecord[] = await getTurns(id)
    // 回放与实时流式共用 turn.ts 的分区/合并/错误渲染规则
    turns.value = history.map(turnFromRecord)
    await scrollToBottom(true)
    // 刷新恢复：挂起轮回放为 waitingApproval，卡片数据经 GET /approvals 补齐（与 409 同路径）
    if (history.some((t) => t.status === 'WAITING_APPROVAL')) await restorePending(id)
  } catch (err) {
    if (err instanceof ApiError && err.notFound) {
      globalError.value = CONV_NOT_FOUND
      dropConversation(id)
    } else if (!authFailed(err)) {
      throw err
    }
  }
}

/* ============ 会话模式切换器（modes Task 4） ============ */

const activeConversation = computed(() => conversations.value.find((c) => c.id === activeId.value))

/** 当前会话模式：无会话/未知值按 STANDARD 兜底渲染（与后端 ChatMode.parse 容错一致） */
const activeMode = computed(() => activeConversation.value?.mode ?? 'STANDARD')

/** 纯聊档占位提示 */
const inputPlaceholder = computed(() => (isChatOnly(activeMode.value) ? PLACEHOLDER_CHAT_ONLY : PLACEHOLDER_DEFAULT))

const switchingMode = ref(false)

/**
 * 切档：PUT /mode 成功 → 仅本地更新对应会话项的 mode（高亮与占位即时变化，不整表刷新）；
 * 409 = 存在待审批轮（先决议再切档，不落档）；404 = 会话不可用（摘除回欢迎态）；
 * 401 走全局横幅，其余错误进全局横幅但不打断聊天。
 */
const switchMode = async (mode: string) => {
  if (!activeId.value || switchingMode.value || mode === activeMode.value) return
  const convId = activeId.value
  switchingMode.value = true
  try {
    const { id, mode: applied } = await putConversationMode(convId, mode)
    const target = conversations.value.find((c) => c.id === id)
    if (target) target.mode = applied
  } catch (err) {
    if (err instanceof ApiError && err.conflict) {
      globalError.value = MODE_SWITCH_BLOCKED
    } else if (err instanceof ApiError && err.notFound) {
      globalError.value = CONV_NOT_FOUND
      dropConversation(convId)
    } else if (!authFailed(err)) {
      globalError.value = err instanceof Error ? err.message : String(err)
    }
  } finally {
    switchingMode.value = false
  }
}

/**
 * 挂起审批恢复（409 挡回与刷新回放共用）：GET pending → 按 turnId 定位挂起轮，
 * 补齐卡片数据并置横幅；无挂起（他端已决议）则无感收场。
 */
const restorePending = async (convId: number) => {
  try {
    const pending = await getPendingApproval(convId)
    if (!pending) return
    const target = turns.value.find((t) => t.id === pending.turnId)
    if (!target) return
    target.approval = pending
    target.status = 'waitingApproval'
    approvalPending.value = true
    await scrollToBottom(true)
  } catch (err) {
    authFailed(err) // 恢复失败不打断聊天主流程（401 走全局横幅）
  }
}

/** 审批决议提交：POST approvals → 返回的 resume SSE 流喂同一 applySseEvent 合并进原轮次 */
const submitApproval = async (turn: ChatTurn, payload: ApprovalDecisionPayload) => {
  if (!activeId.value || sending.value) return
  // 捕获提交时刻的会话 id（与 send 同因：流式期间可能切换会话）
  const convId = activeId.value
  sending.value = true
  // 卡片即时收起进入续跑流式；applySseEvent 收到首事件亦会流转（双保险）
  turn.status = 'streaming'
  try {
    await streamSse(`/api/conversations/${convId}/approvals`, payload, (ev) => {
      applySseEvent(turn, ev)
    })
    if (turn.status === 'streaming') turn.status = 'done'
    approvalPending.value = false
    refresh()
  } catch (err) {
    if (err instanceof ApiError && err.notFound) {
      failTurn(turn, new Error(CONV_UNAVAILABLE))
      globalError.value = CONV_UNAVAILABLE
      dropConversation(convId)
      await refresh()
    } else {
      authFailed(err)
      failTurn(turn, err)
      if (err instanceof ApiError && err.conflict) {
        // 重复提交/竞态：回落 GET pending，挂起仍在则重渲染卡片
        await restorePending(convId)
      }
    }
  } finally {
    sending.value = false
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
    // 轮次不再卡在 streaming；404/401/409 走无感分支
    if (err instanceof ApiError && err.conflict) {
      // 审批挂起挡回（后端兜底，spec：不锁输入框）：撤回乐观轮、还原输入、
      // 横幅提示并拉 GET /approvals 渲染卡片（与刷新恢复同路径）
      turns.value = turns.value.filter((t) => t !== turn)
      input.value = text
      await restorePending(convId)
    } else if (err instanceof ApiError && err.notFound) {
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
  window.addEventListener('hashchange', syncRoute)
  // 身份候选与会话列表互不依赖，并行拉取（options 失败仅隐藏切换器，不阻塞启动）
  fetchIdentityOptions()
    .then((opts) => { identityOptions.value = opts })
    .catch(() => { identityOptions.value = [] })
  // 会话预拉与路由无关：从设置页返回对话时视图即就绪
  await refresh()
  if (conversations.value.length === 0) await newChat()
  else await select(conversations.value[0].id)
})

onUnmounted(() => window.removeEventListener('hashchange', syncRoute))
</script>

<template>
  <!-- 哈希路由：#/settings/permissions → 设置页；其余 → 对话主视图 -->
  <PermissionSettings v-if="route === 'settings'" />
  <div v-else class="layout">
    <button class="menu-btn" @click="sidebarOpen = !sidebarOpen" aria-label="会话列表">☰</button>

    <aside class="sidebar" :class="{ open: sidebarOpen }" @click.self="sidebarOpen = false">
      <div class="brand">
        <span class="mark"></span>
        <span class="name">LinAgent</span>
      </div>
      <button class="new" @click="newChat">新对话</button>
      <button class="settings-link" @click="openSettings">权限设置</button>
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
      <!-- 审批挂起横幅：409 挡回 / 刷新恢复置位，决议完成自动消解 -->
      <div class="approval-banner" v-if="approvalPending" @click="approvalPending = false">
        有待审批操作，请先处理（点击关闭）
      </div>
      <!-- 会话模式切换器：标题旁三段（自由红警/标准/纯聊），切档 409 走全局横幅 -->
      <div class="chat-header" v-if="activeConversation">
        <span class="chat-title">{{ activeConversation.title }}</span>
        <div class="mode-switch" role="group" aria-label="切换会话模式">
          <button v-for="m in CHAT_MODES" :key="m" class="mode-btn"
                  :class="{ active: m === activeMode, danger: modeMeta(m).danger }"
                  :aria-pressed="m === activeMode" :disabled="switchingMode"
                  :title="modeMeta(m).danger ? '自由档：工具调用免审批，风险自担' : undefined"
                  @click="switchMode(m)">
            {{ modeMeta(m).icon }} {{ modeMeta(m).label }}
          </button>
        </div>
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
          <div class="rail" v-if="turn.thinking || turn.tools.length || turn.text || turn.errorText || turn.approval">
            <ThinkingBlock :content="turn.thinking" :streaming="thinkingActive(turn)" v-if="turn.thinking" />
            <ToolCard v-for="t in turn.tools" :key="t.callId" :tool="t" />
            <ApprovalCard v-if="turn.status === 'waitingApproval' && turn.approval"
                          :turn-id="turn.approval.turnId" :items="turn.approval.items"
                          @submit="submitApproval(turn, $event)" />
            <MessageBubble role="assistant" :content="turn.text"
                           :streaming="turn.status === 'streaming' && !!turn.text" v-if="turn.text" />
            <p class="silent-done" v-else-if="executedWithoutText(turn)">✅ 已执行（模型未返回文本回复）</p>
            <p class="error-line" v-if="turn.errorText">{{ turn.errorText }}</p>
          </div>
        </article>
      </div>

      <div class="composer">
        <textarea v-model="input" @keydown.enter.exact.prevent="send"
                  :placeholder="inputPlaceholder" :disabled="sending" rows="2" />
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
.settings-link {
  border: none;
  background: transparent;
  color: var(--ink-faint);
  border-radius: var(--radius-md);
  padding: 6px 0;
  margin: 0 0 14px;
  font-size: 12.5px;
  cursor: pointer;
}
.settings-link:hover { color: var(--pine); }
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

/* 模型空正文边缘（思考里答完即止）：工具已成功的轻提示 */
.silent-done {
  margin: 4px 0;
  color: var(--text-tertiary, #8a8f98);
  font-size: 13px;
}

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

.approval-banner {
  margin: 0 16px 8px;
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--pine-soft);
  color: var(--pine-deep);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}

/* ============ 会话模式切换器 ============ */
/* 标题+切换器左簇布局：右端留空避让 fixed 身份切换器（窄桌面也不重叠） */
.chat-header {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  max-width: calc(var(--content-width) + 48px);
  margin: 0 auto;
  padding: 12px 24px 2px;
}
.chat-title {
  flex: 0 1 auto;
  min-width: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-soft);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mode-switch {
  flex: none;
  display: flex;
  gap: 2px;
  padding: 3px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface);
}
.mode-btn {
  border: none;
  background: transparent;
  color: var(--ink-faint);
  border-radius: 999px;
  padding: 4px 12px;
  font-size: 12.5px;
  white-space: nowrap;
  cursor: pointer;
}
.mode-btn:hover { color: var(--ink); }
.mode-btn.active {
  background: var(--pine-soft);
  color: var(--pine-deep);
  font-weight: 600;
}
.mode-btn:disabled { opacity: 0.55; cursor: default; }
/* 自由档红警：免审批高危，idle 红字 / active 红底，与另两档形成风险对比 */
.mode-btn.danger { color: var(--danger); }
.mode-btn.danger.active {
  background: var(--danger-soft);
  color: var(--danger);
  font-weight: 600;
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
  /* 顶部让位给 fixed ☰ 与身份切换器：标题隐藏，header 承接原 timeline 的顶部间距 */
  .chat-header { padding: 58px 16px 2px; }
  .chat-title { display: none; }
  .timeline { padding: 8px 16px 8px; }
  .composer { padding: 10px 16px 16px; }
  .wordmark { font-size: 40px; }
  .rail { padding-left: 20px; }
}
</style>
