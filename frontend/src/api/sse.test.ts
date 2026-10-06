import { describe, expect, it, vi } from 'vitest'
import { parseFrames, readEventStream } from './sse'

describe('server-sent event parsing', () => {
  it('reads an event name and its data', () => {
    const { frames, rest } = parseFrames('event:driver-moved\ndata:{"driverId":3}\n\n')
    expect(frames).toEqual([{ event: 'driver-moved', data: '{"driverId":3}' }])
    expect(rest).toBe('')
  })

  it('keeps a half-received frame for the next chunk', () => {
    const first = parseFrames('event:driver-moved\ndata:{"driver')
    expect(first.frames).toEqual([])

    const second = parseFrames(first.rest + 'Id":3}\n\n')
    expect(second.frames[0].data).toBe('{"driverId":3}')
  })

  it('reads several frames out of one chunk', () => {
    const { frames } = parseFrames('event:hello\ndata:connected\n\nevent:heartbeat\ndata:1\n\n')
    expect(frames.map((frame) => frame.event)).toEqual(['hello', 'heartbeat'])
  })

  it('joins a multi-line data field and ignores comments', () => {
    const { frames } = parseFrames(':keep-alive\nevent:note\ndata:line one\ndata:line two\n\n')
    expect(frames).toEqual([{ event: 'note', data: 'line one\nline two' }])
  })

  it('defaults to the message event when none is named', () => {
    const { frames } = parseFrames('data:plain\n\n')
    expect(frames[0].event).toBe('message')
  })

  it('reads frames off a response body and sends the token', async () => {
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode('event:hello\ndata:connected\n\n'))
        controller.close()
      },
    })
    const fetchStub = vi.fn().mockResolvedValue(new Response(body, { status: 200 }))
    globalThis.fetch = fetchStub as unknown as typeof fetch
    const seen: string[] = []

    await readEventStream('/api/tracking/stream', {
      signal: new AbortController().signal,
      token: 'token-9',
      onFrame: (frame) => seen.push(frame.event),
    })

    expect(seen).toEqual(['hello'])
    // This is why the stream is read with fetch at all: EventSource cannot send this header.
    expect((fetchStub.mock.calls[0][1].headers as Record<string, string>).Authorization).toBe('Bearer token-9')
  })

  it('rejects when the server refuses the connection', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(new Response('', { status: 403 })) as unknown as typeof fetch

    await expect(
      readEventStream('/api/tracking/stream', {
        signal: new AbortController().signal,
        token: null,
        onFrame: () => undefined,
      }),
    ).rejects.toThrow('HTTP 403')
  })
})
