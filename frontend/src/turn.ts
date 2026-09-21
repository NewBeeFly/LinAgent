import type { ChatTurn, MessageRecord, SseEvent, TurnRecord } from './types'

/**
 * 轮次状态的纯逻辑收口：实时 SSE 事件应用（send 路径）与历史回放
 * （select 路径）共用同一套分区/合并/错误渲染规则，App.vue 只做编排。
 */

export function newTurn(userText: string, status: ChatTurn['status'] = 'streaming'): ChatTurn {
  return { userText, thinking: '', text: '', tools: [], status, errorText: undefined }
}

/** SSE 事件 → 轮次分区（thinking / message / tools / 状态） */
export function applySseEvent(turn: ChatTurn, ev: SseEvent): void {
  if (ev.event === 'thinking_delta') turn.thinking += ev.data.content
  else if (ev.event === 'message_delta') turn.text += ev.data.content
  else if (ev.event === 'tool_call') {
    turn.tools.push({ callId: ev.data.callId, toolName: ev.data.toolName, arguments: ev.data.arguments })
  } else if (ev.event === 'tool_result') {
    const target = turn.tools.find(x => x.callId === ev.data.callId)
    if (target) Object.assign(target, ev.data)
  } else if (ev.event === 'turn_done') turn.status = 'done'
  else if (ev.event === 'error') failTurn(turn, errorTextOf(ev.data))
}

/** 网络失败 / 错误事件收口：状态置 error 并记录错误文本（红色提示行渲染） */
export function failTurn(turn: ChatTurn, err: unknown): void {
  turn.status = 'error'
  turn.errorText = err instanceof Error ? err.message : String(err)
}

/**
 * 思考折叠块的展开态：思考进行中（轮次仍在流式且正文未开始）默认展开，
 * 正文开始出现或轮次结束时自动合上（用户仍可手动开合）。
 */
export function thinkingActive(turn: ChatTurn): boolean {
  return turn.status === 'streaming' && !turn.text
}

/** 历史回放：一条轮记录 → 前端轮次（ERROR → errorText，SUMMARY 跳过） */
export function turnFromRecord(record: TurnRecord): ChatTurn {
  const turn = newTurn('', 'done')
  for (const m of record.messages) applyMessageRecord(turn, m)
  return turn
}

function applyMessageRecord(turn: ChatTurn, m: MessageRecord): void {
  if (m.msgType === 'USER') turn.userText = m.content ?? ''
  else if (m.msgType === 'THINKING') turn.thinking += (m.content ?? '') + '\n'
  else if (m.msgType === 'TEXT') turn.text += m.content ?? ''
  else if (m.msgType === 'TOOL_CALL') {
    turn.tools.push({ callId: m.callId ?? '', toolName: m.toolName ?? '', arguments: m.arguments })
  } else if (m.msgType === 'TOOL_RESULT') {
    const target = turn.tools.find(x => x.callId === m.callId)
    if (target) Object.assign(target, { result: m.result, success: m.success, durationMs: m.durationMs })
  } else if (m.msgType === 'ERROR') {
    failTurn(turn, m.content ?? '未知错误')
  }
  // SUMMARY：压缩摘要只进模型记忆，不进展示层
}

function errorTextOf(data: any): string {
  return data?.message ?? data?.code ?? '未知错误'
}
