import { describe, it, expect, vi } from 'vitest'
import { streamSse, parseSseBlock } from '../sse'

describe('parseSseBlock', () => {
  it('parses event and json data', () => {
    const ev = parseSseBlock('event:tool_call\ndata:{"callId":"c1"}')!
    expect(ev.event).toBe('tool_call')
    expect(ev.data.callId).toBe('c1')
  })

  it('handles multi-line data', () => {
    const ev = parseSseBlock('event:message_delta\ndata:{"content":\ndata:"你好"}')!
    expect(ev.data.content).toBe('你好')
  })

  it('ignores comment and empty blocks', () => {
    expect(parseSseBlock(':keepalive')).toBeNull()
    expect(parseSseBlock('')).toBeNull()
  })
})

describe('streamSse chunking', () => {
  it('handles split chunks and half lines', async () => {
    const chunks = [
      'event:meta\ndata:{"turnId":1}\n\nevent:message_de',
      'lta\ndata:{"content":"A"}\n\n',
    ]
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      body: new ReadableStream({
        start(controller) {
          const enc = new TextEncoder()
          for (const c of chunks) controller.enqueue(enc.encode(c))
          controller.close()
        },
      }),
    })
    vi.stubGlobal('fetch', fetchMock)

    const events: any[] = []
    await streamSse('/api/x', { content: 'hi' }, (e) => events.push(e))

    expect(events.map(e => e.event)).toEqual(['meta', 'message_delta'])
    expect(events[1].data.content).toBe('A')
  })
})
