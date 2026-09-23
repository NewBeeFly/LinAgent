<script setup lang="ts">
import { computed, reactive } from 'vue'
import { batchRememberLevel, type ApprovalChoice } from '../turn'
import type { ApprovalDecisionPayload, ApprovalItem } from '../types'

/**
 * 审批卡片：逐项展示命令/路径 + 子命令判定明细（看得见「为什么问」），
 * 四动作（批准 / 拒绝+理由 / 本会话不再问 / 永久允许·附规则预览），
 * 组装整批决议 {items, remember} 交由父组件 POST——返回的 resume SSE 流
 * 继续喂 turn.ts 合并进原轮次。
 */
const props = defineProps<{ turnId: number; items: ApprovalItem[] }>()
const emit = defineEmits<{ submit: [payload: ApprovalDecisionPayload] }>()

// 逐项选择，默认全部批准；拒绝展开理由输入，永久允许展示 suggestedRule 预览
const choices = reactive<Record<string, ApprovalChoice>>(
  Object.fromEntries(props.items.map((i) => [i.callId, 'approve' as ApprovalChoice])))
const reasons = reactive<Record<string, string>>({})

// remember 为整批档位（后端 DTO 语义），取最弱档（min：宁可少记不可多记，终审 I2）——
// 任一普通批准即 once，防止单项「批准」被同批的「永久允许」静默升级成 forever 规则
const remember = computed<'once' | 'session' | 'forever'>(() =>
  batchRememberLevel(props.items.map((i) => choices[i.callId])))

const submit = () => {
  emit('submit', {
    remember: remember.value,
    items: props.items.map((i) => {
      const reject = choices[i.callId] === 'reject'
      const reason = reject ? (reasons[i.callId] ?? '').trim() : undefined
      return reject
        ? { callId: i.callId, decision: 'reject' as const, ...(reason ? { reason } : {}) }
        : { callId: i.callId, decision: 'approve' as const }
    }),
  })
}

const setAll = (choice: ApprovalChoice) => {
  for (const i of props.items) choices[i.callId] = choice
}

// 卡片主展示：提取后的命令/路径优先，缺省回退原始 arguments JSON
const display = (item: ApprovalItem) => item.payload || item.arguments

const SOURCE_LABELS: Record<string, string> = {
  BUILTIN: '内置白名单',
  session: '本会话规则',
  user: '持久规则',
}
const verdictText = (sv: { allowed: boolean; source?: string | null }) =>
  sv.allowed ? `放行（${SOURCE_LABELS[sv.source ?? ''] ?? '已命中规则'}）` : '需审批'
</script>

<template>
  <div class="approval-card">
    <div class="head">
      <span class="badge">审批</span>
      <span class="title">以下操作需要你的确认</span>
    </div>

    <div v-for="item in items" :key="item.callId" class="item">
      <div class="cmd">
        <span class="tool">{{ item.toolName }}</span>
        <code class="payload">{{ display(item) }}</code>
      </div>
      <ul v-if="item.subVerdicts?.length" class="verdicts">
        <li v-for="(sv, i) in item.subVerdicts" :key="i" :class="{ allowed: sv.allowed }">
          <code>{{ sv.segment || '（空）' }}</code>
          <span>{{ verdictText(sv) }}</span>
        </li>
      </ul>
      <div class="actions" role="group" :aria-label="`${item.toolName} 决议`">
        <button :class="{ on: choices[item.callId] === 'approve' }" @click="choices[item.callId] = 'approve'">批准</button>
        <button :class="{ on: choices[item.callId] === 'reject' }" @click="choices[item.callId] = 'reject'">拒绝</button>
        <button :class="{ on: choices[item.callId] === 'session' }" @click="choices[item.callId] = 'session'">本会话不再问</button>
        <button :class="{ on: choices[item.callId] === 'forever' }" @click="choices[item.callId] = 'forever'">永久允许</button>
      </div>
      <div v-if="choices[item.callId] === 'reject'" class="reason">
        <input v-model="reasons[item.callId]" placeholder="拒绝理由（回传给模型，可留空）" />
      </div>
      <p v-if="choices[item.callId] === 'forever' && item.suggestedRule" class="rule-preview">
        将记住规则 <code>{{ item.suggestedRule }}</code>（可在权限设置中管理）
      </p>
    </div>

    <div class="foot">
      <!-- 单项已是完整决议面，批量快捷键仅多项时出现 -->
      <template v-if="items.length > 1">
        <button @click="setAll('approve')">全部批准</button>
        <button @click="setAll('reject')">全部拒绝</button>
      </template>
      <button class="primary" @click="submit">提交决议</button>
    </div>
  </div>
</template>

<style scoped>
.approval-card {
  border: 1px solid var(--pine);
  border-radius: var(--radius-md);
  background: var(--surface);
  padding: 12px 14px;
  margin: 6px 0;
}
.head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.badge {
  background: var(--pine);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  border-radius: var(--radius-sm);
  padding: 2px 8px;
  flex: none;
}
.title { font-size: 13.5px; color: var(--ink); font-weight: 500; }

.item { padding: 8px 0; border-top: 1px solid var(--line); }
.item:first-of-type { border-top: none; }
.cmd { display: flex; align-items: baseline; gap: 8px; min-width: 0; }
.tool {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--ink-soft);
  flex: none;
}
.payload {
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--ink);
  word-break: break-all;
}
.verdicts {
  list-style: none;
  margin: 6px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.verdicts li {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 12px;
  color: var(--danger);
}
.verdicts li.allowed { color: var(--ink-faint); }
.verdicts li code {
  font-family: var(--font-mono);
  word-break: break-all;
}
.verdicts li span { flex: none; }

.actions { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.actions button {
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--ink-soft);
  border-radius: 999px;
  padding: 4px 12px;
  font-size: 12.5px;
  cursor: pointer;
}
.actions button:hover { border-color: var(--pine); color: var(--pine); }
.actions button.on {
  border-color: var(--pine);
  background: var(--pine-soft);
  color: var(--pine-deep);
  font-weight: 500;
}

.reason { margin-top: 8px; }
.reason input {
  width: 100%;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--paper);
  padding: 6px 10px;
  font-family: inherit;
  font-size: 13px;
  color: var(--ink);
}
.reason input:focus-visible {
  outline: none;
  border-color: var(--pine);
  box-shadow: 0 0 0 2px var(--pine-soft);
}
.rule-preview {
  margin: 8px 0 0;
  font-size: 12.5px;
  color: var(--ink-soft);
}
.rule-preview code {
  font-family: var(--font-mono);
  font-size: 12px;
  background: var(--pine-soft);
  border-radius: 4px;
  padding: 1px 6px;
  color: var(--pine-deep);
}

.foot {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  align-items: center;
  border-top: 1px solid var(--line);
  padding-top: 10px;
  margin-top: 2px;
}
.foot button {
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--ink-soft);
  border-radius: var(--radius-sm);
  padding: 6px 14px;
  font-size: 13px;
  cursor: pointer;
}
.foot button:hover { border-color: var(--pine); color: var(--pine); }
.foot .primary {
  border: none;
  background: var(--pine);
  color: #fff;
  font-weight: 500;
}
.foot .primary:hover { background: var(--pine-deep); color: #fff; }
</style>
