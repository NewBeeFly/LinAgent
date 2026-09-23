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
    const { reactive, computed } = await import('vue')
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

describe('审批中断（approval_request 事件）', () => {
  const pendingItem = {
    callId: 'c1', toolName: 'shell', arguments: '{"command":"mkdir demo"}', payload: 'mkdir demo',
    subVerdicts: [{ segment: 'mkdir demo', allowed: false, source: null }],
    suggestedRule: 'mkdir demo *',
  }

  it('approval_request 归入 approval 分区，状态置 waitingApproval（流尾无 turn_done，不置 done）', () => {
    const turn = newTurn('建目录')
    applySseEvent(turn, { event: 'approval_request', data: { turnId: 7, conversationId: 1, items: [pendingItem] } })

    expect(turn.status).toBe('waitingApproval')
    expect(turn.approval?.turnId).toBe(7)
    expect(turn.approval?.items).toEqual([pendingItem])
    expect(turn.approval!.items[0].suggestedRule).toBe('mkdir demo *')
  })

  it('meta 事件回填轮次 id（409 恢复按 id 定位挂起轮）', () => {
    const turn = newTurn('你好')
    applySseEvent(turn, { event: 'meta', data: { turnId: 42, conversationId: 1, model: 'step-3' } })
    expect(turn.id).toBe(42)
  })

  it('两段流合并：chat 段中断后，resume 段事件并入同一轮（重复 tool_call 按 callId 去重，turn_done 完成）', () => {
    const turn = newTurn('建目录')
    // —— chat 段：思考 → 待审调用 → 流尾 approval_request ——
    applySseEvent(turn, { event: 'thinking_delta', data: { content: '需要建目录' } })
    applySseEvent(turn, { event: 'tool_call', data: { callId: 'c1', toolName: 'shell', arguments: '{"command":"mkdir demo"}' } })
    applySseEvent(turn, { event: 'approval_request', data: { turnId: 7, conversationId: 1, items: [pendingItem] } })
    expect(turn.status).toBe('waitingApproval')

    // —— resume 段（同 turnId 新 SSE 流）：meta → 同 callId 执行轨迹 → 结果 → 正文 → 收尾 ——
    applySseEvent(turn, { event: 'meta', data: { turnId: 7, conversationId: 1 } })
    applySseEvent(turn, { event: 'tool_call', data: { callId: 'c1', toolName: 'shell', arguments: '{"command":"mkdir demo"}' } })
    applySseEvent(turn, { event: 'tool_result', data: { callId: 'c1', result: 'ok', success: true, durationMs: 3 } })
    applySseEvent(turn, { event: 'message_delta', data: { content: '已创建' } })
    applySseEvent(turn, { event: 'turn_done', data: { finishReason: 'STOP' } })

    expect(turn.status).toBe('done')
    expect(turn.tools).toHaveLength(1) // 同 callId 去重合并，不产生重复工具卡
    expect(turn.tools[0]).toMatchObject({ callId: 'c1', result: 'ok', success: true, durationMs: 3 })
    expect(turn.text).toBe('已创建')
    expect(turn.thinking).toBe('需要建目录')
  })

  it('waitingApproval 轮再收 turn_done → 状态流转完成', () => {
    const turn = newTurn('你好')
    applySseEvent(turn, { event: 'approval_request', data: { turnId: 1, conversationId: 1, items: [pendingItem] } })
    applySseEvent(turn, { event: 'turn_done', data: {} })
    expect(turn.status).toBe('done')
  })
})

describe('审批中断的历史回放（select 路径）', () => {
  it('WAITING_APPROVAL 轮回放为 waitingApproval 挂起态（卡片数据由 GET /approvals 补齐）', () => {
    const turn = turnFromRecord({
      id: 9, seq: 2, status: 'WAITING_APPROVAL', finishReason: undefined,
      messages: [
        { seq: 0, msgType: 'USER', content: '建目录' },
        { seq: 1, msgType: 'TOOL_CALL', callId: 'c1', toolName: 'shell', arguments: '{"command":"mkdir demo"}' },
      ],
    })
    expect(turn.id).toBe(9)
    expect(turn.status).toBe('waitingApproval')
    expect(turn.tools).toHaveLength(1)
  })
})

describe('thinkingActive（思考折叠块展开态）', () => {
  it('思考中（流式且无正文）为 true——默认展开', async () => {
    const { thinkingActive } = await import('../turn')
    const turn = newTurn('你好')
    applySseEvent(turn, { event: 'thinking_delta', data: { content: '思' } })
    expect(thinkingActive(turn)).toBe(true)
  })

  it('正文开始即 false——自动合上', async () => {
    const { thinkingActive } = await import('../turn')
    const turn = newTurn('你好')
    applySseEvent(turn, { event: 'thinking_delta', data: { content: '思' } })
    applySseEvent(turn, { event: 'message_delta', data: { content: '答' } })
    expect(thinkingActive(turn)).toBe(false)
  })

  it('轮次结束为 false（含 error 路径）', async () => {
    const { thinkingActive } = await import('../turn')
    const done = newTurn('你好', 'done')
    expect(thinkingActive(done)).toBe(false)
    const failed = newTurn('你好')
    applySseEvent(failed, { event: 'error', data: { code: 'X' } })
    expect(thinkingActive(failed)).toBe(false)
  })
})

describe('batchRememberLevel（审批整批 remember 档位，min 语义）', () => {
  it('任一普通批准 → once：同批「永久允许」不得把「批准」项静默升级成 forever 规则', async () => {
    const { batchRememberLevel } = await import('../turn')
    expect(batchRememberLevel(['approve', 'forever'])).toBe('once')
    expect(batchRememberLevel(['session', 'approve'])).toBe('once')
    expect(batchRememberLevel(['reject', 'approve', 'session', 'forever'])).toBe('once')
  })

  it('无普通批准、任一会话 → session', async () => {
    const { batchRememberLevel } = await import('../turn')
    expect(batchRememberLevel(['session', 'forever'])).toBe('session')
    expect(batchRememberLevel(['reject', 'session'])).toBe('session')
  })

  it('全会话以上 → forever；reject 不参与记忆，全拒批时档位值无实际写入', async () => {
    const { batchRememberLevel } = await import('../turn')
    expect(batchRememberLevel(['forever', 'forever'])).toBe('forever')
    expect(batchRememberLevel(['reject'])).toBe('forever') // 后端仅对 approve 项写规则，无副作用
  })
})
