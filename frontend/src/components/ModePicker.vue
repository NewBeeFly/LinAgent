<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { CHAT_MODES, modeMeta } from '../modes'

/**
 * 会话模式下拉选择器（内嵌 composer 底部工具行，紧凑范式）：
 * 触发器只展示当前档（AUTO 红警态红字常驻），菜单向上弹开避免被视口底部裁剪。
 */
const props = defineProps<{ mode: string; disabled?: boolean }>()
const emit = defineEmits<{ (e: 'change', mode: string): void }>()

const open = ref(false)
const rootEl = ref<HTMLElement | null>(null)

/** 当前档元数据（触发器文案/红警态） */
const current = computed(() => modeMeta(props.mode))

/** 每档一句能力差异说明（菜单内展开说明，触发器保持极简） */
const MODE_DESC: Record<string, string> = {
  AUTO: '工具免审批直接执行，风险自担',
  STANDARD: '工具调用逐次审批后执行',
  CHAT: '纯对话，不使用任何工具',
}

/** 每档线性图标（stroke path 集，currentColor 跟随文字色：自由档在菜单内呈红警） */
const ICON_PATHS: Record<string, string[]> = {
  AUTO: ['M7 11V7a4 4 0 0 1 7.5-1.8', 'M5.5 11h13v8.5h-13z'],
  STANDARD: ['M12 3l7 2.8v5.4c0 4.3-2.9 7.3-7 8.9-4.1-1.6-7-4.6-7-8.9V5.8z'],
  CHAT: ['M21 11.5a8.5 8.5 0 0 1-8.5 8.5c-1.5 0-2.9-.4-4.1-1L3 20.5l1.5-5.4a8.5 8.5 0 1 1 16.5-3.6z'],
}

const toggle = () => {
  if (!props.disabled) open.value = !open.value
}

const pick = (m: string) => {
  open.value = false
  if (m !== props.mode) emit('change', m)
}

// 点击外部 / Esc 收起（监听挂 window，rootEl.contains 排除组件自身点击）
const onDocPointer = (e: Event) => {
  if (open.value && rootEl.value && !rootEl.value.contains(e.target as Node)) open.value = false
}
const onEsc = (e: KeyboardEvent) => {
  if (e.key === 'Escape') open.value = false
}

onMounted(() => {
  window.addEventListener('click', onDocPointer)
  window.addEventListener('keydown', onEsc)
})
onBeforeUnmount(() => {
  window.removeEventListener('click', onDocPointer)
  window.removeEventListener('keydown', onEsc)
})
</script>

<template>
  <div class="picker" ref="rootEl">
    <button class="trigger" :class="{ danger: current.danger }" :disabled="disabled"
            :aria-expanded="open" aria-haspopup="listbox"
            :title="current.danger ? '自由档：工具调用免审批，风险自担' : undefined"
            @click="toggle">
      <svg class="glyph" viewBox="0 0 24 24" aria-hidden="true">
        <path v-for="(d, i) in ICON_PATHS[mode] ?? ICON_PATHS.STANDARD" :key="i" :d="d" />
      </svg>
      <span>{{ current.label }}</span>
      <svg class="caret" viewBox="0 0 24 24" aria-hidden="true"><path d="M6 9l6 6 6-6" /></svg>
    </button>

    <ul v-if="open" class="menu" role="listbox" aria-label="切换会话模式">
      <li v-for="m in CHAT_MODES" :key="m" role="option" :aria-selected="m === mode">
        <button class="option" :class="{ danger: modeMeta(m).danger }" :disabled="disabled" @click="pick(m)">
          <svg class="glyph" viewBox="0 0 24 24" aria-hidden="true">
            <path v-for="(d, i) in ICON_PATHS[m]" :key="i" :d="d" />
          </svg>
          <span class="option-text">
            <span class="option-label">{{ modeMeta(m).label }}</span>
            <span class="option-desc">{{ MODE_DESC[m] }}</span>
          </span>
          <svg v-if="m === mode" class="check" viewBox="0 0 24 24" aria-hidden="true">
            <path d="M5 13l4 4L19 7" />
          </svg>
        </button>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.picker { position: relative; }

.trigger {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: none;
  background: transparent;
  color: var(--ink-soft);
  font-size: 13px;
  padding: 5px 8px;
  border-radius: 8px;
  cursor: pointer;
  white-space: nowrap;
}
.trigger:hover { background: var(--paper); color: var(--ink); }
.trigger[aria-expanded='true'] { background: var(--paper); color: var(--ink); }
.trigger:disabled { opacity: 0.55; cursor: default; }
/* 自由档红警：激活期间触发器整体红字常驻 */
.trigger.danger { color: var(--danger); }
.trigger.danger:hover { background: var(--danger-soft); color: var(--danger); }

.glyph, .caret, .check {
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
  flex: none;
}
.glyph { width: 14px; height: 14px; }
.caret { width: 11px; height: 11px; stroke-width: 2; transition: transform 0.15s ease; }
.trigger[aria-expanded='true'] .caret { transform: rotate(180deg); }
.check { width: 13px; height: 13px; stroke-width: 2.2; color: var(--pine); }

/* 向上弹出的菜单（composer 贴视口底部） */
.menu {
  position: absolute;
  left: 0;
  bottom: calc(100% + 8px);
  z-index: 20;
  min-width: 264px;
  max-width: calc(100vw - 32px);
  margin: 0;
  padding: 5px;
  list-style: none;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  box-shadow: 0 10px 28px rgba(30, 42, 38, 0.12);
}

.option {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  border: none;
  background: transparent;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  text-align: left;
}
.option:hover { background: var(--pine-soft); }
.option:disabled { opacity: 0.55; cursor: default; }
.option .glyph { color: var(--ink-soft); }
.option.danger .glyph { color: var(--danger); }
.option-text { display: flex; flex-direction: column; gap: 1px; min-width: 0; }
.option-label { font-size: 13.5px; font-weight: 600; color: var(--ink); }
.option-desc { font-size: 12px; color: var(--ink-faint); }
.option .check { margin-left: auto; }
</style>
