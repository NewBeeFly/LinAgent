<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { createPermissionRule, deletePermissionRule, listPermissionRules } from '../api/rest'
import type { PermissionRuleItem } from '../api/rest'

/**
 * 权限设置页（#/settings/permissions）：用户持久规则 CRUD + 内置白名单只读区。
 * 规则按 (tenant, user) 隔离——列表即当前身份的规则；effect 当前恒 ALLOW（二期 deny 层）。
 */
const rules = ref<PermissionRuleItem[]>([])
const loading = ref(true)
const busy = ref(false)
// 操作反馈：错误红 / 成功绿，随下一次操作覆盖
const message = ref('')
const messageOk = ref(false)

const newTool = ref('shell')
const newPattern = ref('')

// 内置白名单只读区（服务端 PermissionRuleEngine 常量的展示副本——无暴露端点，
// 服务端命令清单变更时需同步此处；只影响展示，判定以服务端为准）
const ALWAYS_ALLOWED_TOOLS = ['read_file', 'list_dir', 'csv_summary', 'read_skill']
const READ_ONLY_COMMANDS = [
  'ls', 'cat', 'pwd', 'head', 'tail', 'grep', 'find', 'wc', 'which', 'diff',
  'stat', 'du', 'echo', 'cd',
  'git status', 'git log', 'git diff', 'git show',
  'python --version', 'python3 --version',
]

const notify = (text: string, ok: boolean) => {
  message.value = text
  messageOk.value = ok
}

const load = async () => {
  loading.value = true
  try {
    rules.value = await listPermissionRules()
  } catch (err) {
    notify(err instanceof Error ? err.message : String(err), false)
  } finally {
    loading.value = false
  }
}

const add = async () => {
  const pattern = newPattern.value.trim()
  if (!pattern) {
    notify('模式不能为空（如 pip install * 或 docs/*）', false)
    return
  }
  busy.value = true
  try {
    await createPermissionRule(newTool.value, pattern)
    newPattern.value = ''
    notify('已添加规则', true)
    await load()
  } catch (err) {
    // 409 = 规则已存在（服务端幂等提示）；400 = pattern 非法
    notify(err instanceof Error ? err.message : String(err), false)
  } finally {
    busy.value = false
  }
}

const remove = async (rule: PermissionRuleItem) => {
  busy.value = true
  try {
    await deletePermissionRule(rule.id)
    notify(`已删除 ${rule.toolName} / ${rule.pattern}`, true)
    await load()
  } catch (err) {
    notify(err instanceof Error ? err.message : String(err), false)
  } finally {
    busy.value = false
  }
}

const back = () => {
  window.location.hash = ''
}

const fmtTime = (s: string) => (s ? s.replace('T', ' ').slice(0, 19) : '')

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <button class="back" @click="back">← 返回对话</button>
      <h1>权限设置</h1>
    </header>

    <p class="msg" v-if="message" :class="{ ok: messageOk }">{{ message }}</p>

    <section class="card">
      <h2>持久允许规则</h2>
      <p class="hint">命中规则的命令/路径不再询问，直接放行。按当前身份隔离。</p>
      <div v-if="loading" class="hint">加载中…</div>
      <table v-else-if="rules.length">
        <thead>
          <tr>
            <th>工具</th>
            <th>模式</th>
            <th>效果</th>
            <th>创建时间</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="rule in rules" :key="rule.id">
            <td class="mono">{{ rule.toolName }}</td>
            <td class="mono pattern">{{ rule.pattern }}</td>
            <td>{{ rule.effect === 'ALLOW' ? '允许' : rule.effect }}</td>
            <td class="time">{{ fmtTime(rule.createdAt) }}</td>
            <td class="ops">
              <button :disabled="busy" @click="remove(rule)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="hint">还没有规则。审批卡片上选「永久允许」也会在这里生成规则。</p>

      <form class="add" @submit.prevent="add">
        <select v-model="newTool" aria-label="工具">
          <option value="shell">shell</option>
          <option value="write_file">write_file</option>
        </select>
        <input v-model="newPattern" placeholder="模式：如 pip install * 或 docs/*" :disabled="busy" />
        <button type="submit" :disabled="busy">添加</button>
      </form>
    </section>

    <section class="card">
      <h2>内置白名单（只读）</h2>
      <p class="hint">以下工具与命令恒放行，不经审批，不可移除。</p>
      <h3>恒放行工具</h3>
      <div class="chips">
        <code v-for="t in ALWAYS_ALLOWED_TOOLS" :key="t" class="chip">{{ t }}</code>
      </div>
      <h3>shell 只读命令</h3>
      <div class="chips">
        <code v-for="c in READ_ONLY_COMMANDS" :key="c" class="chip">{{ c }}</code>
      </div>
    </section>
  </div>
</template>

<style scoped>
.page {
  max-width: var(--content-width);
  margin: 0 auto;
  padding: 36px 24px 48px;
}
.page-head {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 18px;
}
.page-head h1 {
  font-family: var(--font-display);
  font-size: 24px;
  letter-spacing: -0.02em;
  margin: 0;
  color: var(--ink);
}
.back {
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--ink-soft);
  border-radius: var(--radius-md);
  padding: 6px 12px;
  font-size: 13px;
  cursor: pointer;
}
.back:hover { border-color: var(--pine); color: var(--pine); }

.msg {
  border-radius: var(--radius-sm);
  padding: 8px 12px;
  font-size: 13px;
  background: var(--danger-soft);
  color: var(--danger);
}
.msg.ok { background: var(--pine-soft); color: var(--pine-deep); }

.card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  padding: 18px 20px;
  margin-bottom: 18px;
}
.card h2 {
  font-size: 16px;
  margin: 0 0 4px;
  color: var(--ink);
}
.card h3 {
  font-size: 13px;
  margin: 14px 0 6px;
  color: var(--ink-soft);
  font-weight: 600;
}
.hint { font-size: 13px; color: var(--ink-faint); margin: 0 0 12px; }

table {
  border-collapse: collapse;
  width: 100%;
  font-size: 13.5px;
  margin-bottom: 14px;
}
th, td {
  border-bottom: 1px solid var(--line);
  padding: 8px 10px;
  text-align: left;
  vertical-align: top;
}
th {
  color: var(--ink-faint);
  font-weight: 500;
  font-size: 12px;
}
.mono { font-family: var(--font-mono); font-size: 12.5px; }
.pattern { word-break: break-all; color: var(--ink); }
.time { color: var(--ink-faint); font-size: 12.5px; white-space: nowrap; }
.ops button {
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--danger);
  border-radius: var(--radius-sm);
  padding: 3px 10px;
  font-size: 12.5px;
  cursor: pointer;
}
.ops button:hover { border-color: var(--danger); background: var(--danger-soft); }
.ops button:disabled { opacity: 0.5; cursor: default; }

.add { display: flex; gap: 8px; flex-wrap: wrap; }
.add select,
.add input {
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--paper);
  padding: 7px 10px;
  font-family: inherit;
  font-size: 13.5px;
  color: var(--ink);
}
.add select { flex: none; }
.add input {
  flex: 1;
  min-width: 200px;
  font-family: var(--font-mono);
  font-size: 12.5px;
}
.add select:focus-visible,
.add input:focus-visible {
  outline: none;
  border-color: var(--pine);
  box-shadow: 0 0 0 2px var(--pine-soft);
}
.add button {
  border: none;
  background: var(--pine);
  color: #fff;
  border-radius: var(--radius-sm);
  padding: 7px 16px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}
.add button:hover { background: var(--pine-deep); }
.add button:disabled { opacity: 0.5; cursor: default; }

.chips { display: flex; flex-wrap: wrap; gap: 6px; }
.chip {
  font-family: var(--font-mono);
  font-size: 12px;
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 2px 10px;
  color: var(--ink-soft);
}
</style>
