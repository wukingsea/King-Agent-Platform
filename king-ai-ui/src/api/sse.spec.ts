import { describe, expect, it, vi } from 'vitest'
import { streamChat } from './sse'
import { ApiError } from './http'
import type { ChatStreamHandlers } from './types'

const encoder = new TextEncoder()

function streamOf(chunks: string[]): ReadableStream<Uint8Array> {
  return new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)))
      controller.close()
    },
  })
}

function stubFetch(chunks: string[], init: ResponseInit = { status: 200 }) {
  const fetchMock = vi.fn(
    async (_input: RequestInfo | URL, _init?: RequestInit) =>
      new Response(streamOf(chunks), init),
  )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function collector() {
  const events: Array<[string, unknown]> = []
  const handlers: ChatStreamHandlers = {
    onMeta: (value) => events.push(['meta', value]),
    onCitation: (value) => events.push(['citation', value]),
    onReasoning: (value) => events.push(['reasoning', value]),
    onContent: (value) => events.push(['content', value]),
    onToolCall: (value) => events.push(['tool_call', value]),
    onToolResult: (value) => events.push(['tool_result', value]),
    onToolApproval: (value) => events.push(['tool_approval', value]),
    onRetry: (value) => events.push(['retry', value]),
    onDegraded: (value) => events.push(['degraded', value]),
    onUsage: (value) => events.push(['usage', value]),
    onError: (value) => events.push(['error', value]),
    onDone: (value) => events.push(['done', value]),
  }
  return { events, handlers }
}

describe('streamChat', () => {
  it('dispatches events split across network chunks', async () => {
    stubFetch([
      'event: meta\ndata: {"requestId":"r1","sessionId":7,"modelName":"mock"}\n\n'
        + 'event: content\ndata: {"delta":"你',
      '好"}\n\n',
    ])
    const { events, handlers } = collector()

    await streamChat({ path: '/api/chat/send', body: {}, handlers })

    expect(events).toEqual([
      ['meta', { requestId: 'r1', sessionId: 7, modelName: 'mock' }],
      ['content', '你好'],
    ])
  })

  it('flushes a final block that lacks a blank-line terminator', async () => {
    stubFetch(['event: done\ndata: {"status":"COMPLETED"}'])
    const { events, handlers } = collector()

    await streamChat({ path: '/api/chat/send', body: {}, handlers })

    expect(events).toEqual([['done', { status: 'COMPLETED' }]])
  })

  it('ignores unknown events, malformed JSON and empty payloads', async () => {
    stubFetch([
      'event: unknown\ndata: {"x":1}\n\n'
        + 'event: content\ndata: not-json\n\n'
        + 'event: content\ndata:\n\n'
        + 'event: reasoning\ndata: {"delta":""}\n\n',
    ])
    const { events, handlers } = collector()

    await streamChat({ path: '/api/chat/send', body: {}, handlers })

    expect(events).toEqual([])
  })

  it('dispatches every supported event type', async () => {
    stubFetch([
      'event: reasoning\ndata: {"delta":"想一下"}\n\n'
        + 'event: citation\ndata: {"docName":"手册","chunkId":"c1"}\n\n'
        + 'event: tool_call\ndata: {"id":"t1","name":"search","arguments":"{}","source":"MCP"}\n\n'
        + 'event: tool_result\ndata: {"id":"t1","result":"ok","error":false,"durationMs":5}\n\n'
        + 'event: tool_approval\ndata: {"requestId":"r1","callId":"t1","name":"search","arguments":"{}","source":"MCP"}\n\n'
        + 'event: retry\ndata: {"attempt":2,"reset":true}\n\n'
        + 'event: degraded\ndata: {"message":"已切换","reset":true,"contentLength":1}\n\n'
        + 'event: usage\ndata: {"promptTokens":3,"completionTokens":5,"totalTokens":8}\n\n'
        + 'event: error\ndata: {"message":"上游失败"}\n\n'
        + 'event: done\ndata: {"status":"FAILED","error":"上游失败"}\n\n',
    ])
    const { events, handlers } = collector()

    await streamChat({ path: '/api/chat/send', body: {}, handlers })

    expect(events.map(([name]) => name)).toEqual([
      'reasoning',
      'citation',
      'tool_call',
      'tool_result',
      'tool_approval',
      'retry',
      'degraded',
      'usage',
      'error',
      'done',
    ])
    expect(events[0][1]).toBe('想一下')
    expect(events[8][1]).toBe('上游失败')
    expect(events[9][1]).toEqual({ status: 'FAILED', error: '上游失败' })
  })

  it('rejects with the server message when the response is not ok', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        new Response(JSON.stringify({ message: '请求过于频繁' }), {
          status: 429,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    )
    const { handlers } = collector()

    await expect(streamChat({ path: '/api/chat/send', body: {}, handlers }))
      .rejects.toMatchObject({ name: 'ApiError', message: '请求过于频繁', code: 429 })
  })

  it('rejects when the response has no body', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(null, { status: 204 })),
    )
    const { handlers } = collector()

    await expect(streamChat({ path: '/api/chat/send', body: {}, handlers }))
      .rejects.toBeInstanceOf(ApiError)
  })

  it('sends the space header and request body', async () => {
    localStorage.setItem('king_ai_space', '9')
    const fetchMock = stubFetch(['event: done\ndata: {"status":"COMPLETED"}\n\n'])
    const { handlers } = collector()

    await streamChat({ path: '/api/chat/send', body: { message: 'hi' }, handlers })

    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(new Headers(init.headers).get('X-Space-Id')).toBe('9')
    expect(init.body).toBe(JSON.stringify({ message: 'hi' }))
    expect(init.credentials).toBe('same-origin')
  })
})
