/** 前端身份（顶栏切换器可变更）。优先级：localStorage 记忆 > VITE env > default/linmj */
export interface Identity {
  tenantId: string
  userId: string
}

const STORAGE_KEY = 'linagent.identity'
const ENV_TENANT = (import.meta.env.VITE_TENANT_ID as string | undefined) ?? 'default'
const ENV_USER = (import.meta.env.VITE_USER_ID as string | undefined) ?? 'linmj'

const readStored = (): Identity | null => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<Identity>
    if (!parsed.tenantId || !parsed.userId) return null
    return { tenantId: parsed.tenantId, userId: parsed.userId }
  } catch {
    return null // localStorage 不可用或脏数据 → 回退 env
  }
}

let current: Identity = readStored() ?? { tenantId: ENV_TENANT, userId: ENV_USER }

export const currentIdentity = (): Identity => ({ ...current })

export const setIdentity = (tenantId: string, userId: string): void => {
  current = { tenantId, userId }
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(current))
  } catch {
    /* localStorage 不可用则仅内存生效 */
  }
}

export const identityHeaders = (): Record<string, string> => ({
  'x-tenant-id': current.tenantId,
  'x-user-id': current.userId,
})
