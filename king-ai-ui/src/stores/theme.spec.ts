import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useThemeStore } from './theme'

type MediaListener = (event: { matches: boolean }) => void

const listeners: MediaListener[] = []
let systemDark = true

function stubMatchMedia() {
  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockImplementation((query: string) => ({
      matches: systemDark,
      media: query,
      addEventListener: (_: string, listener: MediaListener) => {
        listeners.push(listener)
      },
    })),
  )
}

function emitSystemChange(matches: boolean) {
  systemDark = matches
  listeners.forEach((listener) => listener({ matches }))
}

describe('theme store', () => {
  beforeEach(() => {
    localStorage.clear()
    listeners.length = 0
    systemDark = true
    document.documentElement.className = ''
    stubMatchMedia()
    setActivePinia(createPinia())
  })

  it('defaults to system mode and resolves to dark when OS prefers dark', () => {
    const theme = useThemeStore()
    theme.init()
    expect(theme.mode).toBe('system')
    expect(theme.resolved).toBe('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('switches to light mode, persists it and updates meta theme-color', () => {
    const theme = useThemeStore()
    theme.init()
    theme.setMode('light')
    expect(theme.resolved).toBe('light')
    expect(localStorage.getItem('king-ai-theme')).toBe('light')
    expect(document.documentElement.classList.contains('light')).toBe(true)
    expect(document.querySelector('meta[name="theme-color"]')?.getAttribute('content')).toBe(
      '#f4f6fb',
    )
  })

  it('system mode follows OS changes without touching the stored mode', () => {
    const theme = useThemeStore()
    theme.setMode('system')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    emitSystemChange(false)
    expect(theme.resolved).toBe('light')
    expect(document.documentElement.classList.contains('light')).toBe(true)
    expect(localStorage.getItem('king-ai-theme')).toBe('system')
  })

  it('restores a previously persisted mode', () => {
    localStorage.setItem('king-ai-theme', 'light')
    const theme = useThemeStore()
    theme.init()
    expect(theme.mode).toBe('light')
    expect(theme.resolved).toBe('light')
  })

  it('falls back to system when the stored value is unknown', () => {
    localStorage.setItem('king-ai-theme', 'sepia')
    const theme = useThemeStore()
    expect(theme.mode).toBe('system')
  })
})
