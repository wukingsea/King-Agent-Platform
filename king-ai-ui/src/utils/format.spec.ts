import { describe, expect, it } from 'vitest'
import {
  documentStatus,
  formatDate,
  formatNumber,
  fromNow,
  sessionStatus,
  sourceText,
  statusText,
  truncate,
} from './format'

describe('formatDate', () => {
  it('renders a dash for missing values', () => {
    expect(formatDate(undefined)).toBe('—')
    expect(formatDate('')).toBe('—')
  })

  it('formats with a custom template', () => {
    expect(formatDate('2026-01-02T03:04:05', 'YYYY/MM/DD')).toBe('2026/01/02')
  })
})

describe('fromNow', () => {
  it('renders a dash for missing values', () => {
    expect(fromNow(undefined)).toBe('—')
  })
})

describe('formatNumber', () => {
  it('keeps small numbers untouched', () => {
    expect(formatNumber(0)).toBe('0')
    expect(formatNumber(999)).toBe('999')
  })

  it('abbreviates thousands, ten-thousands and millions', () => {
    expect(formatNumber(1000)).toBe('1.0k')
    expect(formatNumber(12_345)).toBe('1.2w')
    expect(formatNumber(1_500_000)).toBe('1.5M')
  })

  it('treats missing values as zero', () => {
    expect(formatNumber(undefined)).toBe('0')
  })
})

describe('truncate', () => {
  it('leaves short text alone', () => {
    expect(truncate('abc', 4)).toBe('abc')
  })

  it('cuts long text and marks the cut', () => {
    expect(truncate('abcdef', 4)).toBe('abcd…')
  })

  it('returns an empty string for missing text', () => {
    expect(truncate(undefined)).toBe('')
  })
})

describe('label helpers', () => {
  it('maps status codes to Chinese labels', () => {
    expect(statusText(0)).toBe('启用')
    expect(statusText(1)).toBe('停用')
  })

  it('maps chat sources', () => {
    expect(sourceText('PUBLIC')).toBe('公开访客')
    expect(sourceText('ADMIN')).toBe('后台调试')
    expect(sourceText(undefined)).toBe('后台调试')
  })

  it('maps document statuses', () => {
    expect(documentStatus('READY')).toEqual({ text: '已就绪', type: 'success' })
    expect(documentStatus('FAILED')).toEqual({ text: '失败', type: 'danger' })
    expect(documentStatus('')).toEqual({ text: '未知', type: 'info' })
  })

  it('maps session statuses', () => {
    expect(sessionStatus('STREAMING')).toEqual({ text: '生成中', type: 'warning' })
    expect(sessionStatus('STOPPED')).toEqual({ text: '已停止', type: 'info' })
    expect(sessionStatus('UNKNOWN')).toEqual({ text: 'UNKNOWN', type: 'info' })
  })
})
