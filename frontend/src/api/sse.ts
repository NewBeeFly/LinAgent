import { ApiError } from './error'
import { identityHeaders } from './identity'
import type { SseEvent } from '../types'

export function parseSseBlock(block: string): SseEvent | null {
  let event = 'message'
  let data = ''
  for (const line of block.split('\n')) {
    if (line.startsWith(':') || line.trim() === '') continue
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) data += line.slice(5).trim()
  }
  if (!data) return null
  try {
    return { event, data: JSON.parse(data) }
  } catch {
    return null
  }
}

export async function streamSse(
  url: string,
  body: unknown,
  onEvent: (ev: SseEvent) => void,
): Promise<void> {
  const resp = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...identityHeaders() },
    body: JSON.stringify(body),
  })
  if (!resp.ok || !resp.body) {
    throw await ApiError.from(resp)
  }
  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let idx: number
    while ((idx = buffer.indexOf('\n\n')) >= 0) {
      const block = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)
      const ev = parseSseBlock(block)
      if (ev) onEvent(ev)
    }
  }
  const tail = parseSseBlock(buffer)
  if (tail) onEvent(tail)
}
