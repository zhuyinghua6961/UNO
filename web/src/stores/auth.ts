import { defineStore } from 'pinia'
import { ref } from 'vue'
import { AuthError, authApi, errorMessage, type AuthStatus, type User } from '../api/auth'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null)
  const state = ref<'unknown' | 'authenticated' | 'guest' | 'unavailable'>('unknown')
  const capabilities = ref<AuthStatus | null>(null)
  const busy = ref(false)
  const error = ref('')
  let revision = 0
  let restoring: Promise<void> | null = null

  async function loadCapabilities() {
    capabilities.value = null
    try { capabilities.value = await authApi.status() } catch (failure) { error.value = errorMessage(failure) }
  }

  function restore(): Promise<void> {
    if (busy.value) return Promise.resolve()
    if (restoring) return restoring
    const current = revision
    const pending = (async () => {
      try {
        const restored = await authApi.me()
        if (current !== revision) return
        user.value = restored
        state.value = 'authenticated'
      } catch (failure) {
        if (current !== revision) return
        user.value = null
        state.value = failure instanceof AuthError && failure.status === 401 ? 'guest' : 'unavailable'
      }
    })()
    restoring = pending
    void pending.finally(() => { if (restoring === pending) restoring = null })
    return pending
  }

  async function perform(action: () => Promise<unknown>): Promise<boolean> {
    if (busy.value) return false
    revision++
    restoring = null
    busy.value = true
    error.value = ''
    try { await action(); return true } catch (failure) {
      error.value = errorMessage(failure)
      return false
    } finally { busy.value = false }
  }

  function clearUser() { user.value = null; state.value = 'guest' }

  function login(email: string, password: string) {
    return perform(async () => {
      clearUser()
      user.value = await authApi.login(email, password)
      state.value = 'authenticated'
    })
  }

  function logout() {
    return perform(async () => {
      try { await authApi.logout() } catch (failure) {
        if (!(failure instanceof AuthError && failure.status === 401)) throw failure
      }
      clearUser()
    })
  }

  function reset(token: string, password: string) {
    return perform(async () => { await authApi.reset(token, password); clearUser() })
  }

  return { user, state, capabilities, busy, error, loadCapabilities, restore, perform, login, logout, reset }
})
