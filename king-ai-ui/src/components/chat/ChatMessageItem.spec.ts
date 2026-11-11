import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import ChatMessageItem from './ChatMessageItem.vue'
import type { UiMessage } from './ui'

function message(overrides: Partial<UiMessage> = {}): UiMessage {
  return {
    key: 'm-1',
    sessionId: 1,
    role: 'assistant',
    content: '回答内容',
    status: 'COMPLETED',
    createdAt: '2026-01-01T08:00:00Z',
    ...overrides,
  }
}

function mountItem(msg: UiMessage) {
  return mount(ChatMessageItem, {
    props: { message: msg, agentName: '测试助手', userLabel: '张三' },
    global: { plugins: [createPinia(), ElementPlus] },
  })
}

describe('ChatMessageItem', () => {
  it('renders the user bubble with label', () => {
    const wrapper = mountItem(message({ role: 'user', content: '用户提问' }))

    expect(wrapper.find('.user-bubble').text()).toBe('用户提问')
    expect(wrapper.find('.user-avatar').text()).toBe('张')
  })

  it('renders citations with doc name, score and snippet', () => {
    const wrapper = mountItem(message({
      content: '根据文档回答',
      citations: [{
        index: 1,
        chunkId: 11,
        docId: 5,
        docName: '产品手册.pdf',
        knowledgeBaseId: 2,
        knowledgeBaseName: '产品库',
        chunkIndex: 3,
        score: 0.87,
        snippet: '退款需在 30 天内申请',
      }],
    }))

    const citations = wrapper.find('.citations')
    expect(citations.exists()).toBe(true)
    expect(citations.text()).toContain('知识引用')
    expect(citations.text()).toContain('产品手册.pdf')
    expect(citations.text()).toContain('87%')
    expect(citations.text()).toContain('退款需在 30 天内申请')
  })

  it('renders tool trace cards including failures', () => {
    const wrapper = mountItem(message({
      toolTrace: [
        { id: 't1', name: 'search_docs', source: 'mcp', arguments: '{"q":"x"}', result: 'ok', durationMs: 120 },
        { id: 't2', name: 'send_email', error: true, result: '权限不足' },
      ],
    }))

    const cards = wrapper.findAll('.tool-card')
    expect(cards).toHaveLength(2)
    expect(cards[0].text()).toContain('search_docs')
    expect(cards[1].classes()).toContain('fail')
    expect(wrapper.text()).toContain('权限不足')
  })

  it('shows the thinking placeholder while streaming without content', () => {
    const wrapper = mountItem(message({
      content: '',
      streaming: true,
      status: 'STREAMING',
    }))

    expect(wrapper.find('.thinking-placeholder').exists()).toBe(true)
    expect(wrapper.text()).toContain('正在组织回答')
  })

  it('shows the retrying notice while a retry is in flight', () => {
    const wrapper = mountItem(message({ retrying: true }))

    expect(wrapper.text()).toContain('模型请求中断，正在自动重试')
  })

  it('shows the degraded-model notice when not retrying', () => {
    const wrapper = mountItem(message({
      notice: '主模型不可用，已切换至备用模型',
    }))

    expect(wrapper.text()).toContain('主模型不可用，已切换至备用模型')
  })
})
