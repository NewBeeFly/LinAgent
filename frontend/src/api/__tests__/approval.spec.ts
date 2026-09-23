import { describe, it, expect, vi, afterEach } from 'vitest'
import { createPermissionRule, deletePermissionRule, getPendingApproval, listPermissionRules } from '../rest'
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

describe('permission-rules CRUD', () => {
  it('listPermissionRules：GET 拉取规则行', async () => {
    const rules = [{ id: 1, toolName: 'shell', pattern: 'pip install *', effect: 'ALLOW', createdAt: '2026-09-23T10:00:00' }]
    vi.stubGlobal('fetch', mockFetch(200, rules))

    await expect(listPermissionRules()).resolves.toEqual(rules)
    const [url, init] = vi.mocked(fetch).mock.calls[0]
    expect(url).toBe('/api/permission-rules')
    expect(init?.method).toBeUndefined() // GET
  })

  it('createPermissionRule：POST {toolName, pattern} JSON 体', async () => {
    const saved = { id: 2, toolName: 'write_file', pattern: 'docs/*', effect: 'ALLOW', createdAt: '2026-09-23T10:01:00' }
    vi.stubGlobal('fetch', mockFetch(200, saved))

    await expect(createPermissionRule('write_file', 'docs/*')).resolves.toEqual(saved)
    const [url, init] = vi.mocked(fetch).mock.calls[0]
    expect(url).toBe('/api/permission-rules')
    expect(init?.method).toBe('POST')
    expect(JSON.parse(init!.body as string)).toEqual({ toolName: 'write_file', pattern: 'docs/*' })
  })

  it('createPermissionRule 重复规则 → 409 ApiError（幂等提示不裸 500）', async () => {
    vi.stubGlobal('fetch', mockFetch(409, { message: '规则已存在: shell / pip install *' }))
    const err = await createPermissionRule('shell', 'pip install *').catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err.conflict).toBe(true)
    expect(err.message).toBe('规则已存在: shell / pip install *')
  })

  it('deletePermissionRule：DELETE 204 无体成功；非 2xx 抛 ApiError', async () => {
    vi.stubGlobal('fetch', mockFetch(204))
    await expect(deletePermissionRule(5)).resolves.toBeUndefined()
    expect(vi.mocked(fetch).mock.calls[0][0]).toBe('/api/permission-rules/5')

    vi.stubGlobal('fetch', mockFetch(401, { message: '身份无效' }))
    const err = await deletePermissionRule(5).catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err.unauthorized).toBe(true)
  })
})
