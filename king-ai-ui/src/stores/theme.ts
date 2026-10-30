import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import hljsDarkUrl from 'highlight.js/styles/atom-one-dark.css?url'
import hljsLightUrl from 'highlight.js/styles/github.css?url'

export type ThemeMode = 'light' | 'dark' | 'system'
export type ResolvedTheme = 'light' | 'dark'

// 存储键与颜色常量须与 index.html 的首帧内联脚本保持一致（避免浅色主题闪烁）
const STORAGE_KEY = 'king-ai-theme'
const THEME_MODES: ThemeMode[] = ['light', 'dark', 'system']
const THEME_COLOR: Record<ResolvedTheme, string> = {
  light: '#f4f6fb',
  dark: '#070a17',
}
const HLJS_HREF: Record<ResolvedTheme, string> = {
  light: hljsLightUrl,
  dark: hljsDarkUrl,
}

function readStoredMode(): ThemeMode {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    return THEME_MODES.includes(stored as ThemeMode) ? (stored as ThemeMode) : 'system'
  } catch {
    return 'system'
  }
}

function systemPrefersDark(): boolean {
  return (
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-color-scheme: dark)').matches
  )
}

function ensureHljsLink(): HTMLLinkElement | null {
  const existing = document.getElementById('hljs-theme') as HTMLLinkElement | null
  if (existing) return existing
  if (!document.head) return null
  const link = document.createElement('link')
  link.id = 'hljs-theme'
  link.rel = 'stylesheet'
  document.head.appendChild(link)
  return link
}

function ensureMetaThemeColor(): HTMLMetaElement | null {
  const existing = document.querySelector('meta[name="theme-color"]') as HTMLMetaElement | null
  if (existing) return existing
  if (!document.head) return null
  const meta = document.createElement('meta')
  meta.setAttribute('name', 'theme-color')
  document.head.appendChild(meta)
  return meta
}

export const useThemeStore = defineStore('theme', () => {
  const mode = ref<ThemeMode>(readStoredMode())
  const systemDark = ref(systemPrefersDark())

  const resolved = computed<ResolvedTheme>(() =>
    mode.value === 'system' ? (systemDark.value ? 'dark' : 'light') : mode.value,
  )

  function apply() {
    const scheme = resolved.value
    const root = document.documentElement
    root.classList.remove('light', 'dark')
    root.classList.add(scheme)
    ensureMetaThemeColor()?.setAttribute('content', THEME_COLOR[scheme])
    const link = ensureHljsLink()
    if (link) link.setAttribute('href', HLJS_HREF[scheme])
  }

  let systemListenerAttached = false

  function ensureSystemListener() {
    if (systemListenerAttached || typeof window.matchMedia !== 'function') return
    const mql = window.matchMedia('(prefers-color-scheme: dark)')
    // 旧 Safari/WebView 的 MediaQueryList 无 addEventListener，降级为不监听系统切换（主题仍可手动切换）
    if (typeof mql.addEventListener !== 'function') return
    mql.addEventListener('change', (event: MediaQueryListEvent) => {
      systemDark.value = event.matches
      if (mode.value === 'system') apply()
    })
    systemListenerAttached = true
  }

  function setMode(next: ThemeMode) {
    mode.value = next
    try {
      localStorage.setItem(STORAGE_KEY, next)
    } catch {
      /* 隐私模式下写入失败可忽略，仅影响持久化 */
    }
    ensureSystemListener()
    apply()
  }

  function init() {
    ensureSystemListener()
    apply()
  }

  return { mode, systemDark, resolved, setMode, init, apply }
})
