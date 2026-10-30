import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api'
import { SPACE_KEY, getSpaceId } from '@/api/http'
import type { AuthResponse, LoginRequest, SpaceRole, SpaceView, UserView } from '@/api/types'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserView | null>(null)
  const spaces = ref<SpaceView[]>([])
  const currentSpaceId = ref<number | null>(getSpaceId())
  const authenticated = ref(false)
  const checked = ref(false)
  const loading = ref(false)
  let pending: Promise<boolean> | null = null

  const isAdmin = computed(() => user.value?.role === 'ADMIN')
  const currentSpace = computed(
    () => spaces.value.find((space) => space.id === currentSpaceId.value) || null,
  )
  const displayName = computed(
    () => user.value?.nickname || user.value?.username || '未登录',
  )
  const spaceRole = computed<SpaceRole | null>(() => currentSpace.value?.role ?? null)
  // Platform admins bypass space roles; OWNER/ADMIN manage the space, AUDITOR observes governance data only.
  const canManageSpace = computed(
    () => isAdmin.value || spaceRole.value === 'OWNER' || spaceRole.value === 'ADMIN',
  )
  const canAuditSpace = computed(
    () => canManageSpace.value || spaceRole.value === 'AUDITOR',
  )
  const canEditSpace = computed(
    () => canManageSpace.value || spaceRole.value === 'EDITOR',
  )

  function hasSpaceRole(roles: SpaceRole[]): boolean {
    return isAdmin.value || (spaceRole.value != null && roles.includes(spaceRole.value))
  }

  function setSpace(spaceId: number | null | undefined) {
    if (!spaceId) return
    currentSpaceId.value = spaceId
    localStorage.setItem(SPACE_KEY, String(spaceId))
  }

  function resolveSpace(preferred?: number | null) {
    const candidate =
      (preferred && spaces.value.some((space) => space.id === preferred) && preferred) ||
      (currentSpaceId.value &&
        spaces.value.some((space) => space.id === currentSpaceId.value) &&
        currentSpaceId.value) ||
      spaces.value[0]?.id
    if (candidate) setSpace(candidate)
  }

  function applySession(data: AuthResponse) {
    user.value = data.user
    spaces.value = data.spaces || []
    resolveSpace(data.currentSpaceId)
    authenticated.value = data.user != null
    checked.value = true
  }

  async function login(payload: LoginRequest) {
    loading.value = true
    try {
      const data = await authApi.login(payload)
      if (!data.mfaRequired) applySession(data)
      return data
    } finally {
      loading.value = false
    }
  }

  async function verifyMfa(challenge: string, code: string) {
    loading.value = true
    try {
      const data = await authApi.verifyMfa(challenge, code)
      applySession(data)
      return data
    } finally {
      loading.value = false
    }
  }

  async function fetchMe() {
    loading.value = true
    try {
      const data = await authApi.me()
      applySession(data)
      return data
    } catch {
      user.value = null
      spaces.value = []
      authenticated.value = false
      return null
    } finally {
      checked.value = true
      loading.value = false
    }
  }

  async function ensureSession() {
    if (checked.value) return authenticated.value
    if (!pending) {
      pending = fetchMe()
        .then(() => authenticated.value)
        .finally(() => {
          pending = null
        })
    }
    return pending
  }

  function clearLocal() {
    user.value = null
    spaces.value = []
    currentSpaceId.value = null
    authenticated.value = false
    checked.value = true
    localStorage.removeItem(SPACE_KEY)
  }

  async function logout() {
    try {
      await authApi.logout()
    } catch {
      /* ignore network errors on logout */
    } finally {
      clearLocal()
    }
  }

  function switchSpace(spaceId: number) {
    setSpace(spaceId)
  }

  return {
    user,
    spaces,
    currentSpaceId,
    authenticated,
    checked,
    loading,
    isAdmin,
    currentSpace,
    spaceRole,
    canManageSpace,
    canAuditSpace,
    canEditSpace,
    hasSpaceRole,
    displayName,
    login,
    verifyMfa,
    fetchMe,
    ensureSession,
    logout,
    clearLocal,
    switchSpace,
    setSpace,
  }
})
