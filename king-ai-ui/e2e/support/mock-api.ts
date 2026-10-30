import type { Page, Route } from '@playwright/test'

export type MockSpaceRole = 'OWNER' | 'ADMIN' | 'EDITOR' | 'VIEWER' | 'AUDITOR'

export interface MockSpace {
  id: number
  name: string
  code: string
  status: number
  role: MockSpaceRole
  createdAt: string
}

export interface MockUser {
  id: number
  username: string
  nickname: string
  role: 'ADMIN' | 'USER'
  status: number
  spaceIds: number[]
  createdAt: string
}

export interface ApiMockOptions {
  authenticated?: boolean
  user?: MockUser
  spaces?: MockSpace[]
}

export interface ApiMock {
  stopCalls: string[]
  loginCalls: number
}

const CREATED_AT = '2026-10-01T08:00:00'

export const adminUser: MockUser = {
  id: 1,
  username: 'admin',
  nickname: '管理员',
  role: 'ADMIN',
  status: 0,
  spaceIds: [1, 2],
  createdAt: CREATED_AT,
}

export const auditorUser: MockUser = {
  id: 2,
  username: 'auditor',
  nickname: '审计员',
  role: 'USER',
  status: 0,
  spaceIds: [1],
  createdAt: CREATED_AT,
}

export const defaultSpaces: MockSpace[] = [
  { id: 1, name: '总部空间', code: 'hq', status: 0, role: 'OWNER', createdAt: CREATED_AT },
  { id: 2, name: '研发空间', code: 'rnd', status: 0, role: 'OWNER', createdAt: CREATED_AT },
]

const agent = {
  id: 1,
  spaceId: 1,
  name: '测试智能体',
  intro: '用于端到端测试的智能体',
  modelId: 1,
  modelName: 'Mock',
  modelIdentifier: 'mock',
  publishStatus: 0,
  status: 0,
  knowledgeBaseIds: [],
  mcpIds: [],
  skillIds: [],
  createdAt: CREATED_AT,
}

const stats = {
  agents: 1,
  publishedAgents: 0,
  knowledgeBases: 0,
  documents: 0,
  chunks: 0,
  sessions: 0,
  mcpServers: 0,
  skills: 0,
  suppliers: 0,
  models: 1,
}

const emptyPage = {
  list: [],
  total: 0,
  page: 1,
  size: 20,
  totalPages: 0,
  hasNext: false,
  hasPrevious: false,
}

// The stream hands out sessionId 42, so the session list must contain it:
// loadSessions() clears the conversation when the active session disappears.
const session = {
  id: 42,
  agentId: 1,
  agentName: '测试智能体',
  sourceType: 'ADMIN',
  title: '端到端测试会话',
  messageCount: 2,
  lastMessageAt: CREATED_AT,
  createdAt: CREATED_AT,
}

const settings = {
  piiEnabled: true,
  moderationMode: 'OFF',
  inputAction: 'BLOCK',
  outputAction: 'BLOCK',
  blocklist: [],
  customPiiPatterns: [],
}

function ok(route: Route, data: unknown) {
  return route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, message: 'ok', data }),
  })
}

function unauthorized(route: Route) {
  return route.fulfill({
    status: 401,
    contentType: 'application/json',
    body: JSON.stringify({ code: 401, message: '未登录或会话已过期', data: null }),
  })
}

export async function installApiMocks(
  page: Page,
  options: ApiMockOptions = {},
): Promise<ApiMock> {
  const mock: ApiMock = { stopCalls: [], loginCalls: 0 }
  let authenticated = options.authenticated ?? false
  const user = options.user ?? adminUser
  const spaces = options.spaces ?? defaultSpaces

  const authPayload = (route: Route) => {
    const header = route.request().headers()['x-space-id']
    const requested = Number(header)
    const currentSpaceId =
      spaces.find((space) => space.id === requested)?.id ?? spaces[0]?.id
    return { user, spaces, currentSpaceId }
  }

  // Match on the URL path only: a `**/api/**` glob would also swallow Vite dev
  // modules such as /src/api/http.ts and break the app bundle.
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const request = route.request()
      const path = new URL(request.url()).pathname.replace(/^\/api/, '')
      const method = request.method()

      if (path === '/auth/login' && method === 'POST') {
        mock.loginCalls += 1
        authenticated = true
        return ok(route, { ...authPayload(route), currentSpaceId: spaces[0]?.id })
      }
      if (path === '/auth/logout') {
        authenticated = false
        return ok(route, null)
      }
      if (path === '/auth/me') {
        return authenticated ? ok(route, authPayload(route)) : unauthorized(route)
      }
      if (path === '/public/meta') return ok(route, { demoEnabled: false })
      if (path === '/health') {
        return ok(route, { status: 'UP', app: 'king-ai', time: new Date().toISOString() })
      }
      if (path === '/workspace/stats') return ok(route, stats)
      if (path === '/agents' && method === 'GET') {
        return ok(route, { ...emptyPage, list: [agent], total: 1 })
      }
      if (path === '/agents/options') return ok(route, [{ id: 1, name: agent.name }])
      if (path === '/chat/sessions') return ok(route, { ...emptyPage, list: [session], total: 1 })
      if (path === '/mcp-servers') return ok(route, [])
      if (path === '/models') return ok(route, [])
      if (path === '/compliance-settings') return ok(route, settings)
      if (path === '/compliance/retention-policies') return ok(route, [])
      if (path === '/compliance/legal-holds') return ok(route, [])
      if (path === '/compliance/access-reviews') return ok(route, [])
      if (path === '/compliance/exports') return ok(route, [])
      if (path.startsWith('/chat/requests/') && path.endsWith('/stop')) {
        mock.stopCalls.push(path)
        return ok(route, null)
      }
      return ok(route, null)
    },
  )

  return mock
}

/**
 * Replaces window.fetch only for the streaming chat endpoint so tests can drive
 * SSE events frame by frame; axios traffic keeps going through page.route mocks.
 */
export async function installChatStream(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const chat = {
      emit: (_event: string, _data: unknown) => {},
      close: () => {},
      abort: () => {},
    }
    ;(window as unknown as { __chatStream: typeof chat }).__chatStream = chat
    const originalFetch = window.fetch.bind(window)
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url =
        typeof input === 'string'
          ? input
          : input instanceof URL
            ? input.href
            : input.url
      if (!url.endsWith('/api/chat/send')) {
        return originalFetch(input, init)
      }
      const encoder = new TextEncoder()
      const stream = new ReadableStream<Uint8Array>({
        start(controller) {
          chat.emit = (event: string, data: unknown) => {
            controller.enqueue(
              encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`),
            )
          }
          chat.close = () => {
            try {
              controller.close()
            } catch {
              /* already closed */
            }
          }
          chat.abort = () => controller.error(new DOMException('aborted', 'AbortError'))
          setTimeout(() => {
            chat.emit('meta', {
              requestId: 'req-1',
              sessionId: 42,
              agentId: 1,
              agentName: '测试智能体',
              model: 'mock',
              modelName: 'Mock',
              sourceType: 'ADMIN',
            })
          }, 0)
        },
      })
      init?.signal?.addEventListener('abort', () => chat.abort())
      return Promise.resolve(
        new Response(stream, {
          status: 200,
          headers: { 'Content-Type': 'text/event-stream' },
        }),
      )
    }) as typeof window.fetch
  })
}
