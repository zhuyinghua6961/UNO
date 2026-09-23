import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { AuthError, authApi } from '../api/auth'
import { useAuthStore } from './auth'

const profile = { id: 'one', email: 'one@example.com', nickname: '一号' }
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

describe('account lifecycle', () => {
  beforeEach(() => { vi.restoreAllMocks(); setActivePinia(createPinia()) })

  it('deduplicates session restoration', async () => {
    const pending = deferred<typeof profile>()
    const me = vi.spyOn(authApi, 'me').mockReturnValue(pending.promise)
    const auth = useAuthStore()
    const first = auth.restore()
    const second = auth.restore()
    expect(me).toHaveBeenCalledTimes(1)
    pending.resolve(profile)
    await Promise.all([first, second])
    expect(auth.user).toEqual(profile)
    expect(auth.state).toBe('authenticated')
  })

  it('does not let old restoration overwrite a newer login', async () => {
    const pending = deferred<typeof profile>()
    vi.spyOn(authApi, 'me').mockReturnValue(pending.promise)
    const newer = { ...profile, id: 'two' }
    vi.spyOn(authApi, 'login').mockResolvedValue(newer)
    const auth = useAuthStore()
    const restoration = auth.restore()
    await auth.login(profile.email, 'testing password')
    pending.resolve(profile)
    await restoration
    expect(auth.user?.id).toBe('two')
  })

  it('does not resurrect a session after logout', async () => {
    const pending = deferred<typeof profile>()
    vi.spyOn(authApi, 'me').mockReturnValue(pending.promise)
    vi.spyOn(authApi, 'logout').mockResolvedValue(undefined)
    const auth = useAuthStore()
    auth.user = profile
    const restoration = auth.restore()
    expect(await auth.logout()).toBe(true)
    pending.resolve(profile)
    await restoration
    expect(auth.user).toBeNull()
    expect(auth.state).toBe('guest')
  })

  it.each([401, 503])('hides stale user data when restoration returns %s', async status => {
    vi.spyOn(authApi, 'me').mockRejectedValue(new AuthError(status, 'ERROR', 'unavailable'))
    const auth = useAuthStore()
    auth.user = profile
    await auth.restore()
    expect(auth.user).toBeNull()
    expect(auth.state).toBe(status === 401 ? 'guest' : 'unavailable')
  })

  it('keeps a visible account and honest error when logout cannot be confirmed', async () => {
    vi.spyOn(authApi, 'logout').mockRejectedValue(new AuthError(0, 'NETWORK', 'network failed'))
    const auth = useAuthStore()
    auth.user = profile
    auth.state = 'authenticated'
    expect(await auth.logout()).toBe(false)
    expect(auth.user).toEqual(profile)
    expect(auth.error).toBe('network failed')
    expect(auth.busy).toBe(false)
  })

  it('accepts an already expired session as logged out', async () => {
    vi.spyOn(authApi, 'logout').mockRejectedValue(new AuthError(401, 'EXPIRED', 'expired'))
    const auth = useAuthStore()
    auth.user = profile
    expect(await auth.logout()).toBe(true)
    expect(auth.user).toBeNull()
  })

  it('prevents duplicate submissions and background restoration while submitting', async () => {
    const pending = deferred<typeof profile>()
    const login = vi.spyOn(authApi, 'login').mockReturnValue(pending.promise)
    const me = vi.spyOn(authApi, 'me')
    const auth = useAuthStore()
    const first = auth.login(profile.email, 'testing password')
    expect(await auth.login(profile.email, 'testing password')).toBe(false)
    await auth.restore()
    expect(login).toHaveBeenCalledTimes(1)
    expect(me).not.toHaveBeenCalled()
    pending.resolve(profile)
    expect(await first).toBe(true)
  })

  it('clears the current account only after a successful password reset', async () => {
    const reset = vi.spyOn(authApi, 'reset').mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(undefined)
    const auth = useAuthStore()
    auth.user = profile
    expect(await auth.reset('token', 'testing password')).toBe(false)
    expect(auth.user).toEqual(profile)
    expect(await auth.reset('token', 'testing password')).toBe(true)
    expect(auth.user).toBeNull()
    expect(reset).toHaveBeenCalledTimes(2)
  })

  it('fails closed when capabilities cannot be loaded', async () => {
    vi.spyOn(authApi, 'status').mockRejectedValue(new Error('offline'))
    const auth = useAuthStore()
    auth.capabilities = { loginAvailable: true, registrationAvailable: true }
    await auth.loadCapabilities()
    expect(auth.capabilities).toBeNull()
    expect(auth.error).not.toBe('')
  })
})
