import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../error'
import { listConversations } from '../rest'
import { identityHeaders } from '../identity'

describe('ApiError 分类与身份 header', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('非 2xx 解析 JSON message 为 ApiError', async () => {
    vi.stubGlobal('fetch', vi.fn(async () =>
      new Response(JSON.stringify({ message: '会话不存在或无权访问: 9' }), { status: 404 })))
    await expect(listConversations()).rejects.toMatchObject({
      status: 404, message: '会话不存在或无权访问: 9', notFound: true,
    })
  })

  it('非 JSON 错误体回退为 HTTP 状态描述', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('boom', { status: 401 })))
    await expect(listConversations()).rejects.toBeInstanceOf(ApiError)
  })

  it('所有请求携带身份 header', async () => {
    const fetchMock = vi.fn(async () => new Response('[]'))
    vi.stubGlobal('fetch', fetchMock)
    await listConversations()
    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect((init.headers as Record<string, string>)['x-tenant-id']).toBe('default')
    expect((init.headers as Record<string, string>)['x-user-id']).toBe('linmj')
    expect(identityHeaders()).toEqual({ 'x-tenant-id': 'default', 'x-user-id': 'linmj' })
  })
})
