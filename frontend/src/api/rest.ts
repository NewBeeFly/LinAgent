import { ApiError } from './error'
import { identityHeaders } from './identity'

const json = async (url: string, init?: RequestInit) => {
  const resp = await fetch(url, { ...init, headers: { ...identityHeaders(), ...(init?.headers ?? {}) } })
  if (!resp.ok) throw await ApiError.from(resp)
  return resp.json()
}

export const listConversations = () => json('/api/conversations')
export const createConversation = (title?: string) =>
  json('/api/conversations', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title }),
  })
export const getTurns = (id: number) => json(`/api/conversations/${id}/turns`)
export const deleteConversation = async (id: number) => {
  const resp = await fetch(`/api/conversations/${id}`, {
    method: 'DELETE',
    headers: identityHeaders(),
  })
  if (!resp.ok && resp.status !== 404) throw await ApiError.from(resp) // 404 视为已删（幂等）
}
