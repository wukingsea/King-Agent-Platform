import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import 'dayjs/locale/zh-cn'

dayjs.extend(relativeTime)
dayjs.locale('zh-cn')

export function formatDate(value?: string, template = 'YYYY-MM-DD HH:mm'): string {
  if (!value) return '—'
  return dayjs(value).format(template)
}

export function fromNow(value?: string): string {
  if (!value) return '—'
  return dayjs(value).fromNow()
}

export function formatNumber(value?: number): string {
  if (value === undefined || value === null) return '0'
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`
  if (value >= 10_000) return `${(value / 10_000).toFixed(1)}w`
  if (value >= 1_000) return `${(value / 1_000).toFixed(1)}k`
  return String(value)
}

export function truncate(value: string | undefined, max = 80): string {
  if (!value) return ''
  return value.length > max ? `${value.slice(0, max)}…` : value
}

export function greeting(): string {
  const hour = new Date().getHours()
  if (hour < 6) return '夜深了'
  if (hour < 11) return '早上好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
}

export function statusText(status: number): string {
  return status === 0 ? '启用' : '停用'
}

export function sourceText(source?: string): string {
  return source === 'PUBLIC' ? '公开访客' : '后台调试'
}

export function documentStatus(status: string): { text: string; type: 'success' | 'warning' | 'danger' | 'info' } {
  switch (status) {
    case 'READY':
      return { text: '已就绪', type: 'success' }
    case 'PENDING':
      return { text: '排队中', type: 'warning' }
    case 'PROCESSING':
      return { text: '索引中', type: 'warning' }
    case 'FAILED':
      return { text: '失败', type: 'danger' }
    default:
      return { text: status || '未知', type: 'info' }
  }
}

export function sessionStatus(status: string): { text: string; type: 'success' | 'warning' | 'danger' | 'info' } {
  switch (status) {
    case 'COMPLETED':
      return { text: '已完成', type: 'success' }
    case 'STREAMING':
      return { text: '生成中', type: 'warning' }
    case 'STOPPED':
      return { text: '已停止', type: 'info' }
    case 'FAILED':
      return { text: '失败', type: 'danger' }
    default:
      return { text: status || '未知', type: 'info' }
  }
}

export async function copyText(text: string): Promise<void> {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text)
    return
  }
  const input = document.createElement('textarea')
  input.value = text
  input.style.position = 'fixed'
  input.style.opacity = '0'
  document.body.appendChild(input)
  input.select()
  document.execCommand('copy')
  document.body.removeChild(input)
}
