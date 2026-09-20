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
}

export interface ToolEvent {
  callId: string
  toolName: string
  arguments?: string
  result?: string
  success?: boolean
  durationMs?: number
}
