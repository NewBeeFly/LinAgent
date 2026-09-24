/** 会话模式码（与后端 ChatMode 枚举同名同值：AUTO 免审批 / STANDARD 默认审批 / CHAT 无工具纯聊） */
export type ChatModeCode = 'AUTO' | 'STANDARD' | 'CHAT'

/** 单档展示元数据：切换器按钮的图标/文案/高危标（AUTO 免审批红警） */
export interface ModeMeta {
  icon: string
  label: string
  danger: boolean
}

/** 切换器渲染顺序：自由档打头，红警态常驻可视 */
export const CHAT_MODES: ChatModeCode[] = ['AUTO', 'STANDARD', 'CHAT']

const MODE_META: Record<ChatModeCode, ModeMeta> = {
  AUTO: { icon: '🔓', label: '自由', danger: true },
  STANDARD: { icon: '🛡', label: '标准', danger: false },
  CHAT: { icon: '💬', label: '纯聊', danger: false },
}

/** 模式码 → 展示元数据；未知值（DB 脏值/旧数据缺省）回落 STANDARD 兜底，与后端 ChatMode.parse 容错一致 */
export const modeMeta = (mode: string): ModeMeta => MODE_META[mode as ChatModeCode] ?? MODE_META.STANDARD

/** 纯聊档：输入框占位与工具能力提示共用判定 */
export const isChatOnly = (mode: string): boolean => mode === 'CHAT'
