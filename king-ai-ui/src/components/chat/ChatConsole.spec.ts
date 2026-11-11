import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import ChatConsole from './ChatConsole.vue'
import { chatApi, publicChatApi } from '@/api'
import { streamChat } from '@/api/sse'

vi.mock('@/api', () => ({
  chatApi: {
    sessions: vi.fn(),
    messages: vi.fn(),
    rename: vi.fn(),
    remove: vi.fn(),
    approveTool: vi.fn(),
    stop: vi.fn(),
  },
  publicChatApi: {
    sessions: vi.fn(),
    messages: vi.fn(),
    approveTool: vi.fn(),
    stop: vi.fn(),
  },
  authApi: {},
}))

vi.mock('@/api/sse', () => ({
  streamChat: vi.fn(),
}))

const mockedChatApi = vi.mocked(chatApi)
const mockedPublicChatApi = vi.mocked(publicChatApi)
const mockedStreamChat = vi.mocked(streamChat)

function mountConsole(props: Record<string, unknown> = { agentId: 5 }) {
  return mount(ChatConsole, {
    props,
    global: {
      plugins: [createPinia(), ElementPlus],
    },
  })
}

function session(id: number, title: string) {
  return {
    id,
    agentId: 5,
    sourceType: 'ADMIN',
    title,
    messageCount: 3,
    createdAt: '2026-01-01T00:00:00Z',
    lastMessageAt: '2026-01-01T00:00:00Z',
  }
}

async function sendText(wrapper: ReturnType<typeof mountConsole>, text: string) {
  await wrapper.find('textarea').setValue(text)
  await flushPromises()
  await wrapper.find('.send-btn').trigger('click')
  await flushPromises()
}

describe('ChatConsole', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockedChatApi.sessions.mockResolvedValue({ list: [], total: 0 } as never)
    mockedChatApi.messages.mockResolvedValue([])
    mockedPublicChatApi.sessions.mockResolvedValue([])
    mockedPublicChatApi.messages.mockResolvedValue([])
  })

  it('loads the session rail for the selected agent', async () => {
    mockedChatApi.sessions.mockResolvedValue({
      list: [session(1, '会话 A'), session(2, '会话 B')],
      total: 2,
    } as never)

    const wrapper = mountConsole()
    await flushPromises()

    expect(mockedChatApi.sessions).toHaveBeenCalledWith({ agentId: 5, page: 1, size: 50 })
    const items = wrapper.findAll('.session-item')
    expect(items).toHaveLength(2)
    expect(items[0].text()).toContain('会话 A')
  })

  it('streams an assistant reply and renders user + assistant messages', async () => {
    // 发送完成后 loadSessions 会校验 activeSessionId 是否仍在列表中，
    // 因此 mock 需要返回 onMeta 分配的 sessionId=42
    mockedChatApi.sessions.mockResolvedValue({
      list: [session(42, '新对话')],
      total: 1,
    } as never)
    mockedStreamChat.mockImplementation(async ({ handlers }) => {
      handlers.onMeta?.({
        requestId: 'req-1',
        sessionId: 42,
        model: 'm1',
        modelName: '测试模型',
      } as never)
      handlers.onContent?.('助手回复内容')
      handlers.onDone?.({ status: 'COMPLETED', durationMs: 12 } as never)
    })

    const wrapper = mountConsole()
    await flushPromises()
    await sendText(wrapper, '你好')
    await flushPromises()

    expect(mockedStreamChat).toHaveBeenCalledTimes(1)
    const call = mockedStreamChat.mock.calls[0][0]
    expect(call.path).toBe('/api/chat/send')
    expect(call.body).toMatchObject({ agentId: 5, message: '你好' })
    expect(wrapper.text()).toContain('你好')
    expect(wrapper.text()).toContain('助手回复内容')
    // 发送完成后会话列表被刷新
    expect(mockedChatApi.sessions).toHaveBeenCalled()
  })

  it('shows the approval card and submits the decision', async () => {
    const deferred: { resolve?: () => void } = {}
    mockedStreamChat.mockImplementation(({ handlers }) => {
      handlers.onMeta?.({ requestId: 'req-9', sessionId: 7, model: 'm' } as never)
      handlers.onToolApproval?.({
        requestId: 'req-9',
        callId: 'call-1',
        name: 'search_docs',
        source: 'mcp',
        arguments: '{"q":"x"}',
        expiresInMs: 60_000,
      })
      // 保持流不结束，模拟等待人工审批
      return new Promise<void>((resolve) => {
        deferred.resolve = resolve
      })
    })
    mockedChatApi.approveTool.mockResolvedValue(undefined as never)

    const wrapper = mountConsole()
    await flushPromises()
    await sendText(wrapper, '查一下文档')
    await flushPromises()

    expect(wrapper.find('.approval-card').exists()).toBe(true)
    expect(wrapper.text()).toContain('search_docs')

    await wrapper.find('.approval-btn.approve').trigger('click')
    await flushPromises()

    expect(mockedChatApi.approveTool).toHaveBeenCalledWith('req-9', 'call-1', true)
    expect(wrapper.find('.approval-card').exists()).toBe(false)

    deferred.resolve?.()
    await flushPromises()
  })

  it('blocks sending until agentId is provided', async () => {
    const wrapper = mountConsole({ mode: 'admin' })
    await flushPromises()

    await wrapper.find('textarea').setValue('hi')
    await flushPromises()

    const sendButton = wrapper.find('.send-btn')
    expect(sendButton.attributes('disabled')).toBeDefined()
    expect(mockedStreamChat).not.toHaveBeenCalled()
  })
})
