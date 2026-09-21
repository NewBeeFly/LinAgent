import { describe, it, expect } from 'vitest'
import { applySseEvent, failTurn, newTurn, turnFromRecord } from '../turn'
import type { TurnRecord } from '../types'

describe('SSE 事件应用（send 流式路径）', () => {
  it('thinking/message 增量追加，tool_call/tool_result 按 callId 合并', () => {
    const turn = newTurn('你好')
    applySseEvent(turn, { event: 'thinking_delta', data: { content: '思' } })
    applySseEvent(turn, { event: 'message_delta', data: { content: '回' } })
    applySseEvent(turn, { event: 'tool_call', data: { callId: 'c1', toolName: 'list_dir', arguments: '{"path":"."}' } })
    applySseEvent(turn, { event: 'tool_result', data: { callId: 'c1', result: 'a.txt', success: true, durationMs: 5 } })
    applySseEvent(turn, { event: 'turn_done', data: {} })

    expect(turn.thinking).toBe('思')
    expect(turn.text).toBe('回')
    expect(turn.tools).toEqual([
      { callId: 'c1', toolName: 'list_dir', arguments: '{"path":"."}', result: 'a.txt', success: true, durationMs: 5 },
    ])
    expect(turn.status).toBe('done')
  })

  it('error 事件置 error 状态并把 message 记录为错误文本', () => {
    const turn = newTurn('你好')
    applySseEvent(turn, { event: 'error', data: { code: 'AGENT_ERROR', message: '模型连接失败' } })
    expect(turn.status).toBe('error')
    expect(turn.errorText).toBe('模型连接失败')
  })

  it('error 事件 message 缺省时退回 code', () => {
    const turn = newTurn('你好')
    applySseEvent(turn, { event: 'error', data: { code: 'AGENT_ERROR' } })
    expect(turn.status).toBe('error')
    expect(turn.errorText).toBe('AGENT_ERROR')
  })
})

describe('网络失败路径（send 的 catch）', () => {
  it('failTurn 把 streaming 轮收口为 error 并记录 Error.message', () => {
    const turn = newTurn('你好')
    expect(turn.status).toBe('streaming')
    failTurn(turn, new Error('HTTP 500'))
    expect(turn.status).toBe('error')
    expect(turn.errorText).toBe('HTTP 500')
  })

  it('failTurn 对非 Error 载荷转字符串记录', () => {
    const turn = newTurn('你好')
    failTurn(turn, 'fetch failed')
    expect(turn.status).toBe('error')
    expect(turn.errorText).toBe('fetch failed')
  })
})

describe('历史回放（select 路径）', () => {
  const record = (messages: any[]): TurnRecord =>
    ({ id: 1, seq: 1, status: 'COMPLETED', finishReason: 'STOP', messages })

  it('ERROR 消息映射到该轮 errorText（与 send 失败同一渲染路径）', () => {
    const turn = turnFromRecord(record([
      { seq: 0, msgType: 'USER', content: '列出文件' },
      { seq: 1, msgType: 'ERROR', content: 'java.lang.RuntimeException: 模型超时' },
    ]))
    expect(turn.userText).toBe('列出文件')
    expect(turn.status).toBe('error')
    expect(turn.errorText).toBe('java.lang.RuntimeException: 模型超时')
  })

  it('SUMMARY 消息跳过不渲染', () => {
    const turn = turnFromRecord(record([
      { seq: 0, msgType: 'USER', content: '问题' },
      { seq: 1, msgType: 'SUMMARY', content: '此前对话摘要' },
      { seq: 2, msgType: 'TEXT', content: '回答' },
    ]))
    expect(turn.text).toBe('回答')
    expect(turn.thinking).toBe('')
    expect(turn.errorText).toBeUndefined()
  })

  it('各分区按 msgType 回填，TOOL_RESULT 按 callId 合并到 TOOL_CALL', () => {
    const turn = turnFromRecord(record([
      { seq: 0, msgType: 'USER', content: '列出文件' },
      { seq: 1, msgType: 'THINKING', content: '思考' },
      { seq: 2, msgType: 'TOOL_CALL', callId: 'c1', toolName: 'list_dir', arguments: '{"path":"."}' },
      { seq: 3, msgType: 'TOOL_RESULT', callId: 'c1', result: 'a.txt', success: true, durationMs: 5 },
      { seq: 4, msgType: 'TEXT', content: '有 1 个文件' },
    ]))
    expect(turn.userText).toBe('列出文件')
    expect(turn.thinking).toBe('思考\n')
    expect(turn.text).toBe('有 1 个文件')
    expect(turn.tools).toEqual([
      { callId: 'c1', toolName: 'list_dir', arguments: '{"path":"."}', result: 'a.txt', success: true, durationMs: 5 },
    ])
  })
})

describe('响应式触发（回归：流式增量必须驱动重渲染）', () => {
  it('reactive turn 上的 applySseEvent 变更会触发依赖更新（raw 对象不会——这正是一次性渲染的根因）', async () => {
    const { reactive, computed, nextTick } = await import('vue')
    const turn = reactive(newTurn('你好'))
    let evaluations = 0
    const renderedText = computed(() => {
      evaluations++
      return turn.text
    })
    expect(renderedText.value).toBe('')
    const baseline = evaluations

    applySseEvent(turn, { event: 'message_delta', data: { content: '第一' } })
    applySseEvent(turn, { event: 'message_delta', data: { content: '段' } })

    expect(renderedText.value).toBe('第一段')
    expect(evaluations).toBeGreaterThan(baseline)
  })

  it('reactive turn 的 tools push 同样触发依赖更新', async () => {
    const { reactive, computed } = await import('vue')
    const turn = reactive(newTurn('你好'))
    const toolCount = computed(() => turn.tools.length)
    expect(toolCount.value).toBe(0)

    applySseEvent(turn, { event: 'tool_call', data: { callId: 'c1', toolName: 'list_dir', arguments: '{}' } })

    expect(toolCount.value).toBe(1)
  })
})
