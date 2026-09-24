import { describe, it, expect, vi, afterEach } from 'vitest'
import { CHAT_MODES, isChatOnly, modeMeta } from '../modes'
import { putConversationMode } from '../api/rest'
import { ApiError } from '../api/error'

// 统一 fetch 替身：resp.json() 在 ok / ApiError.from(resp) 两条路径都可能被调用
const mockFetch = (status: number, body?: unknown) =>
  vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  })

afterEach(() => vi.unstubAllGlobals())

describe('modeMeta 模式码 → 展示元数据映射', () => {
  it('三档各有独立图标/文案，AUTO 标红警', () => {
    expect(modeMeta('AUTO')).toEqual({ icon: '🔓', label: '自由', danger: true })
    expect(modeMeta('STANDARD')).toEqual({ icon: '🛡', label: '标准', danger: false })
    expect(modeMeta('CHAT')).toEqual({ icon: '💬', label: '纯聊', danger: false })
  })

  it('未知值（DB 脏值/旧数据缺省）回落 STANDARD 兜底，不抛错', () => {
    expect(modeMeta('')).toEqual({ icon: '🛡', label: '标准', danger: false })
    expect(modeMeta('auto')).toEqual(modeMeta('STANDARD')) // 大小写敏感：未归一即未知
    expect(modeMeta('BOGUS')).toEqual(modeMeta('STANDARD'))
  })

  it('CHAT_MODES 渲染顺序：自由/标准/纯聊（红警档打头）', () => {
    expect(CHAT_MODES).toEqual(['AUTO', 'STANDARD', 'CHAT'])
  })

  it('isChatOnly：仅 CHAT 为 true（占位提示判定）', () => {
    expect(isChatOnly('CHAT')).toBe(true)
    expect(isChatOnly('AUTO')).toBe(false)
    expect(isChatOnly('STANDARD')).toBe(false)
    expect(isChatOnly('')).toBe(false)
  })
})

describe('putConversationMode（PUT /api/conversations/{id}/mode）', () => {
  it('200 → 解析 {id, mode}（规范化后的枚举名）', async () => {
    vi.stubGlobal('fetch', mockFetch(200, { id: 3, mode: 'CHAT' }))

    await expect(putConversationMode(3, 'CHAT')).resolves.toEqual({ id: 3, mode: 'CHAT' })
    const [url, init] = vi.mocked(fetch).mock.calls[0]
    expect(url).toBe('/api/conversations/3/mode')
    expect(init?.method).toBe('PUT')
    expect(init?.headers).toMatchObject({ 'Content-Type': 'application/json' })
    expect(JSON.parse(init!.body as string)).toEqual({ mode: 'CHAT' })
  })

  it('409（有待审批轮挡回）→ ApiError.conflict，携带后端 message', async () => {
    vi.stubGlobal('fetch', mockFetch(409, { message: '存在待审批操作，请先完成审批', conversationId: 3 }))
    const err = await putConversationMode(3, 'AUTO').catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err.conflict).toBe(true)
    expect(err.message).toBe('存在待审批操作，请先完成审批')
  })

  it('404（非属主）→ ApiError.notFound；401 → unauthorized', async () => {
    vi.stubGlobal('fetch', mockFetch(404, { message: '会话不存在' }))
    const notFound = await putConversationMode(9, 'CHAT').catch((e) => e)
    expect(notFound.notFound).toBe(true)

    vi.stubGlobal('fetch', mockFetch(401, { message: '身份无效' }))
    const unauthorized = await putConversationMode(9, 'CHAT').catch((e) => e)
    expect(unauthorized.unauthorized).toBe(true)
  })
})
