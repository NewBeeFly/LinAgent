import { afterEach, describe, expect, it, vi } from 'vitest'

/** identity 优先级契约：localStorage 记忆 > VITE env > default/linmj；切换即时生效并持久化 */
const store = new Map<string, string>()
const localStorageStub = {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => void store.set(k, v),
  removeItem: (k: string) => void store.delete(k),
}

describe('identity 优先级与切换', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.unstubAllEnvs()
    vi.resetModules()
    store.clear()
  })

  const load = () => import('../identity')

  it('无记忆无 env 时回退 default/linmj，header 读当前身份', async () => {
    vi.stubGlobal('localStorage', localStorageStub)
    const id = await load()
    expect(id.currentIdentity()).toEqual({ tenantId: 'default', userId: 'linmj' })
    expect(id.identityHeaders()).toEqual({ 'x-tenant-id': 'default', 'x-user-id': 'linmj' })
  })

  it('env 身份作为无记忆时的回退（VITE 覆盖 default）', async () => {
    vi.stubGlobal('localStorage', localStorageStub)
    vi.stubEnv('VITE_TENANT_ID', 'acme')
    vi.stubEnv('VITE_USER_ID', 'bob')
    const id = await load()
    expect(id.currentIdentity()).toEqual({ tenantId: 'acme', userId: 'bob' })
  })

  it('localStorage 记忆优先于 env', async () => {
    vi.stubGlobal('localStorage', localStorageStub)
    vi.stubEnv('VITE_TENANT_ID', 'acme')
    store.set('linagent.identity', JSON.stringify({ tenantId: 'default', userId: 'tester' }))
    const id = await load()
    expect(id.currentIdentity()).toEqual({ tenantId: 'default', userId: 'tester' })
  })

  it('setIdentity 即时生效、持久化，模块重载后仍记住', async () => {
    vi.stubGlobal('localStorage', localStorageStub)
    const first = await load()
    first.setIdentity('acme', 'alice')
    expect(first.identityHeaders()).toEqual({ 'x-tenant-id': 'acme', 'x-user-id': 'alice' })

    vi.resetModules()
    const second = await load()
    expect(second.currentIdentity()).toEqual({ tenantId: 'acme', userId: 'alice' })
  })

  it('localStorage 不可用时不崩溃，回退 env 身份', async () => {
    // node 环境无 localStorage，不 stub 即缺失场景
    const id = await load()
    expect(id.currentIdentity()).toEqual({ tenantId: 'default', userId: 'linmj' })
    expect(() => id.setIdentity('a', 'b')).not.toThrow()
    expect(id.identityHeaders()['x-tenant-id']).toBe('a')
  })
})
