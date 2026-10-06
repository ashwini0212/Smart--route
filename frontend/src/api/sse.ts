/**
 * A server-sent event reader built on `fetch`, because `EventSource` cannot send an Authorization header.
 *
 * The stream is protected like every other endpoint, and the access token lives in memory rather than in a
 * cookie (see client.ts), so the browser's own EventSource has no way to authenticate. The alternatives were
 * putting the token in the query string — where it lands in access logs and in the browser's history — or
 * accepting the refresh cookie on this one endpoint, which would make it the only endpoint that can be
 * called by any page on the internet the user happens to visit. Reading the body ourselves costs this file
 * and the reconnection that EventSource would have given us for free.
 */

export interface SseFrame {
  event: string
  data: string
}

/** Splits a raw SSE text chunk stream into frames. Exported for the test, and because the parsing is the subtle part. */
export function parseFrames(buffer: string): { frames: SseFrame[]; rest: string } {
  const frames: SseFrame[] = []
  // Frames are separated by a blank line; a chunk can end mid-frame, so the remainder is kept for next time.
  const parts = buffer.split(/\n\n|\r\n\r\n/)
  const rest = parts.pop() ?? ''
  for (const part of parts) {
    let event = 'message'
    const data: string[] = []
    for (const line of part.split(/\r?\n/)) {
      if (line.startsWith('event:')) event = line.slice('event:'.length).trim()
      else if (line.startsWith('data:')) data.push(line.slice('data:'.length).trim())
      // ':' comment lines and unknown fields are ignored, as the SSE specification says.
    }
    if (data.length > 0) frames.push({ event, data: data.join('\n') })
  }
  return { frames, rest }
}

export interface StreamOptions {
  signal: AbortSignal
  token: string | null
  onFrame: (frame: SseFrame) => void
  onOpen?: () => void
}

/** Reads the stream until it ends or the signal aborts. Resolves on a clean end; rejects on a failed connection. */
export async function readEventStream(path: string, options: StreamOptions): Promise<void> {
  const headers: Record<string, string> = { Accept: 'text/event-stream' }
  if (options.token) headers.Authorization = `Bearer ${options.token}`
  const response = await fetch(path, { headers, signal: options.signal })
  if (!response.ok || !response.body) {
    throw new Error(`Live stream refused the connection (HTTP ${response.status})`)
  }
  options.onOpen?.()
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) return
    buffer += decoder.decode(value, { stream: true })
    const { frames, rest } = parseFrames(buffer)
    buffer = rest
    frames.forEach(options.onFrame)
  }
}
