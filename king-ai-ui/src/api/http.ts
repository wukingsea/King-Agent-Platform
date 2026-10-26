import axios, { type AxiosRequestConfig } from 'axios'
import type { ApiResponse } from './types'

export const SPACE_KEY = 'king_ai_space'

// Earlier builds stored the bearer token in localStorage; drop any leftover copy now that the session lives in an HttpOnly cookie.
localStorage.removeItem('king_ai_token')

export class ApiError extends Error {
  code: number

  constructor(message: string, code = 0) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

function notifyUnauthorized() {
  localStorage.removeItem(SPACE_KEY)
  window.dispatchEvent(new Event('king:unauthorized'))
}

export function getSpaceId(): number | null {
  const raw = localStorage.getItem(SPACE_KEY)
  if (!raw) return null
  const value = Number(raw)
  return Number.isFinite(value) && value > 0 ? value : null
}

export function authHeaders(): Record<string, string> {
  const headers: Record<string, string> = {}
  const spaceId = getSpaceId()
  if (spaceId) headers['X-Space-Id'] = String(spaceId)
  return headers
}

const http = axios.create({
  baseURL: '/api',
  timeout: 60_000,
  withCredentials: true,
})

http.interceptors.request.use((config) => {
  const headers = authHeaders()
  Object.entries(headers).forEach(([key, value]) => {
    config.headers.set(key, value)
  })
  return config
})

http.interceptors.response.use(
  (response) => {
    const payload = response.data as ApiResponse<unknown>
    if (payload && typeof payload.code === 'number') {
      if (payload.code === 200) return payload.data
      if (payload.code === 401) notifyUnauthorized()
      throw new ApiError(payload.message || '请求失败', payload.code)
    }
    return response.data
  },
  (error) => {
    const payload = error.response?.data as ApiResponse<unknown> | undefined
    const status = Number(error.response?.status || payload?.code || 0)
    const url = String(error.config?.url || '')
    if (status === 401 && !url.includes('/auth/login') && !url.includes('/auth/me')) {
      notifyUnauthorized()
    }
    if (status === 428 && !window.location.hash.startsWith('#/change-password')) {
      window.location.hash = '#/change-password'
    }
    if (payload?.message) {
      return Promise.reject(new ApiError(payload.message, status))
    }
    if (error.code === 'ECONNABORTED') {
      return Promise.reject(new ApiError('请求超时，请稍后重试', status))
    }
    return Promise.reject(new ApiError(error.message || '网络异常，请检查服务是否启动', status))
  },
)

export function request<T>(config: AxiosRequestConfig): Promise<T> {
  return http.request(config) as unknown as Promise<T>
}

export default http
