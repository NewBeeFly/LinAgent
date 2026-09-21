import { ApiError } from './error'
import { authedFetch } from './identity'

const json = async <T>(url: string, init?: RequestInit): Promise<T> => {
  const resp = await authedFetch(url, init)
  if (!resp.ok) throw await ApiError.from(resp)
  return resp.json()
}

export interface IdentityOption {
  userId: string
  name: string
}

export interface TenantIdentityOptions {
  tenantId: string
  users: IdentityOption[]
}

/** 身份候选名单（后端 /api/identity/options，免鉴权） */
export const fetchIdentityOptions = () => json<TenantIdentityOptions[]>('/api/identity/options')

export const listConversations = () => json('/api/conversations')
export const createConversation = (title?: string) =>
  json('/api/conversations', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title }),
  })
export const getTurns = (id: number) => json(`/api/conversations/${id}/turns`)
export const deleteConversation = async (id: number) => {
  const resp = await authedFetch(`/api/conversations/${id}`, { method: 'DELETE' })
  if (!resp.ok && resp.status !== 404) throw await ApiError.from(resp) // 404 视为已删（幂等）
}
