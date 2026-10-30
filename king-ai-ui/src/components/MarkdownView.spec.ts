import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import MarkdownView from './MarkdownView.vue'

async function render(content: string) {
  const wrapper = mount(MarkdownView, { props: { content } })
  await nextTick()
  return wrapper
}

describe('MarkdownView', () => {
  it('renders markdown structure', async () => {
    const wrapper = await render('## 标题\n\n**加粗** 与 `code`')

    expect(wrapper.find('h2').text()).toBe('标题')
    expect(wrapper.find('strong').text()).toBe('加粗')
    expect(wrapper.find('code').text()).toBe('code')
  })

  it('strips scripts and event handlers from model output', async () => {
    const wrapper = await render(
      '<script>window.__pwned = true</script>'
        + '<img src="x" onerror="window.__pwned = true">'
        + '正文',
    )

    const html = wrapper.html()
    expect(html).not.toContain('<script')
    expect(html).not.toContain('onerror')
    expect(html).toContain('正文')
  })

  it('drops javascript: links instead of rendering them', async () => {
    const wrapper = await render('[点我](javascript:alert(1))')

    expect(wrapper.html()).not.toContain('javascript:')
  })

  it('opens external links in a new tab without a window handle', async () => {
    const wrapper = await render('[文档](https://example.com/docs)')

    const anchor = wrapper.find('a')
    expect(anchor.attributes('href')).toBe('https://example.com/docs')
    expect(anchor.attributes('target')).toBe('_blank')
    expect(anchor.attributes('rel')).toBe('noopener noreferrer')
  })

  it('renders fenced code blocks as pre/code', async () => {
    const wrapper = await render('```json\n{"ok":true}\n```')

    expect(wrapper.find('pre code').exists()).toBe(true)
    expect(wrapper.text()).toContain('"ok":true')
  })
})
