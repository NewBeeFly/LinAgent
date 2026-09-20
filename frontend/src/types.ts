export interface SseEvent {
  event: string
  data: any
}

export interface TurnRecord {
  id: number
  seq: number
  status: string
  finishReason?: string
  messages: MessageRecord[]
}

export interface MessageRecord {
  seq: number
  msgType: 'USER' | 'THINKING' | 'TEXT' | 'TOOL_CALL' | 'TOOL_RESULT' | 'ERROR' | 'SUMMARY'
  content?: string
  callId?: string
  toolName?: string
  arguments?: string
  result?: string
  success?: boolean
  durationMs?: number
}

export interface ChatTurn {
  userText: string
  thinking: string
  text: string
  tools: ToolEvent[]
  status: 'streaming' | 'done' | 'error'
  /** 错误提示行（网络失败 / SSE error 事件 / 历史回放 ERROR 消息），渲染为红色提示 */
  errorText?: string
}

export interface ToolEvent {
  callId: string
  toolName: string
  arguments?: string
  result?: string
  success?: boolean
  durationMs?: number
}
