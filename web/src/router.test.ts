import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { AuthError, authApi } from './api/auth'
import { useAuthStore } from './stores/auth'
import { router } from './router'

describe('protected account route', () => {
  beforeEach(async () => { setActivePinia(createPinia()); await router.push('/') })
  afterEach(() => vi.restoreAllMocks())

  it('redirects an expired session to login with a local return path', async () => {
    vi.spyOn(authApi, 'me').mockRejectedValue(new AuthError(401, 'EXPIRED', 'expired'))
    await router.push('/account')
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.next).toBe('/account')
  })

  it('admits an authenticated user after checking the server', async () => {
    vi.spyOn(authApi, 'me').mockResolvedValue({ id: 'user', email: 'user@example.com', nickname: '玩家' })
    await router.push('/account')
    expect(router.currentRoute.value.path).toBe('/account')
    expect(useAuthStore().state).toBe('authenticated')
  })

  it('shows retry state without displaying stale account data during an outage', async () => {
    vi.spyOn(authApi, 'me').mockRejectedValue(new AuthError(503, 'UNAVAILABLE', 'unavailable'))
    useAuthStore().user = { id: 'stale', email: 'user@example.com', nickname: '旧账号' }
    await router.push('/account')
    expect(router.currentRoute.value.path).toBe('/account')
    expect(useAuthStore().state).toBe('unavailable')
    expect(useAuthStore().user).toBeNull()
  })
})
