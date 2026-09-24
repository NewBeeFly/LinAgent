import { ApiError } from './error'
import { authedFetch } from './identity'
import type { PendingApproval, TurnRecord } from '../types'

/** 会话摘要行（GET /api/conversations 元素 / POST 创建响应同形）；mode 为三档模式码（modes V8） */
export interface ConversationSummary {
  id: number
  title: string
  mode: string
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

/** 切档响应：{id, mode}（mode 为规范化后的枚举名） */
export interface ModeUpdateResult {
  id: number
  mode: string
}

/** 切换会话模式：200 回 {id, mode}；非法值 400；归属 404；存在待审批轮 409（body 与审批 409 同形，按本接口挂掉区分语义） */
export const putConversationMode = (id: number, mode: string) =>
  json<ModeUpdateResult>(`/api/conversations/${id}/mode`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mode }),
  })
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

/** 用户审批规则行（GET/POST /api/permission-rules 响应同形） */
export interface PermissionRuleItem {
  id: number
  toolName: string
  pattern: string
  effect: string
  createdAt: string
}

export const listPermissionRules = () => json<PermissionRuleItem[]>('/api/permission-rules')

/** 创建规则：tool 仅 shell | write_file；重复规则服务端 409（ApiError.conflict） */
export const createPermissionRule = (toolName: string, pattern: string) =>
  json<PermissionRuleItem>('/api/permission-rules', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ toolName, pattern }),
  })

/** 删除规则：服务端 (tenant, user, id) 三元收敛，统一 204 无响应体 */
export const deletePermissionRule = async (id: number) => {
  const resp = await authedFetch(`/api/permission-rules/${id}`, { method: 'DELETE' })
  if (!resp.ok) throw await ApiError.from(resp)
}
