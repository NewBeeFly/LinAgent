const json = async (url: string, init?: RequestInit) => {
  const resp = await fetch(url, init)
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
  return resp.json()
}

export const listConversations = () => json('/api/conversations')
export const createConversation = (title?: string) =>
  json('/api/conversations', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title }),
  })
export const deleteConversation = (id: number) =>
  fetch(`/api/conversations/${id}`, { method: 'DELETE' })
export const getTurns = (id: number) => json(`/api/conversations/${id}/turns`)
