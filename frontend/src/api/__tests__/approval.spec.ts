import { describe, it, expect, vi, afterEach } from 'vitest'
import { getPendingApproval } from '../rest'
import { ApiError } from '../error'

// 统一 fetch 替身：resp.json() 在 ok / ApiError.from(resp) 两条路径都可能被调用
const mockFetch = (status: number, body?: unknown) =>
  vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  })

afterEach(() => vi.unstubAllGlobals())

describe('getPendingApproval（GET /api/conversations/{id}/approvals）', () => {
  it('200 → 解析 {turnId, items}（卡片渲染数据源）', async () => {
    const pending = {
      turnId: 7,
      items: [{ callId: 'c1', toolName: 'shell', arguments: '{"command":"mkdir demo"}', payload: 'mkdir demo', subVerdicts: [], suggestedRule: 'mkdir demo *' }],
    }
    vi.stubGlobal('fetch', mockFetch(200, pending))

    const result = await getPendingApproval(3)

    expect(result).toEqual(pending)
    const [url, init] = vi.mocked(fetch).mock.calls[0]
    expect(url).toBe('/api/conversations/3/approvals')
    expect(init?.method).toBeUndefined() // GET
  })

  it('204 → null（无挂起审批，恢复路径无感收场）', async () => {
    vi.stubGlobal('fetch', mockFetch(204))
    await expect(getPendingApproval(3)).resolves.toBeNull()
  })

  it('非 2xx → ApiError（携带后端 message）', async () => {
    vi.stubGlobal('fetch', mockFetch(409, { message: '会话无待审批轮次', conversationId: 3 }))
    const err = await getPendingApproval(3).catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err.conflict).toBe(true)
    expect(err.message).toBe('会话无待审批轮次')
  })
})
