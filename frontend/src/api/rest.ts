import { ApiError } from './error'
import { authedFetch } from './identity'
import type { PendingApproval, TurnRecord } from '../types'

/** 会话摘要行（GET /api/conversations 元素 / POST 创建响应同形） */
export interface ConversationSummary {
  id: number
  title: string
  turnCount: number
  updatedAt: string
}

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

export const listConversations = () => json<ConversationSummary[]>('/api/conversations')
export const createConversation = (title?: string) =>
  json<ConversationSummary>('/api/conversations', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title }),
  })
export const getTurns = (id: number) => json<TurnRecord[]>(`/api/conversations/${id}/turns`)
export const deleteConversation = async (id: number) => {
  const resp = await authedFetch(`/api/conversations/${id}`, { method: 'DELETE' })
  if (!resp.ok && resp.status !== 404) throw await ApiError.from(resp) // 404 视为已删（幂等）
}

/** 会话待审批详情：无挂起 → 204 → null（409 挡回与刷新恢复共用同一渲染路径） */
export const getPendingApproval = async (conversationId: number): Promise<PendingApproval | null> => {
  const resp = await authedFetch(`/api/conversations/${conversationId}/approvals`)
  if (!resp.ok) throw await ApiError.from(resp)
  if (resp.status === 204) return null
  return resp.json()
}
