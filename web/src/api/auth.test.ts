import { describe, expect, it, vi } from 'vitest'
import { createAuthApi } from './auth'

const profile = { id: 'user-1', email: 'player@example.com', nickname: '玩家' }
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })

describe('Web account transport', () => {
  it('gets fresh CSRF before every mutation and uses only same-origin cookies', async () => {
    const transport = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'first' }))
      .mockResolvedValueOnce(json({ user: profile }))
      .mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'second' }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    const api = createAuthApi(transport)
    expect(await api.login(profile.email, 'long password for testing')).toEqual(profile)
    await api.logout()
    expect(transport.mock.calls.map(call => call[0])).toEqual(['/api/auth/csrf', '/api/auth/login', '/api/auth/csrf', '/api/auth/logout'])
    expect(transport.mock.calls[1]![1]?.headers).toEqual({ 'Content-Type': 'application/json', 'X-CSRF-TOKEN': 'first' })
    expect(transport.mock.calls[3]![1]?.headers).toEqual({ 'Content-Type': 'application/json', 'X-CSRF-TOKEN': 'second' })
    for (const [, options] of transport.mock.calls) {
      expect(options).toMatchObject({ credentials: 'same-origin', cache: 'no-store', redirect: 'error' })
      expect(options?.signal).toBeInstanceOf(AbortSignal)
      expect(new Headers(options?.headers).has('Authorization')).toBe(false)
      expect(new Headers(options?.headers).has('X-UNO-Client')).toBe(false)
    }
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })

  it('does not submit with a malformed CSRF response', async () => {
    const transport = vi.fn<typeof fetch>().mockResolvedValue(json({ headerName: 'Authorization', token: 'unsafe' }))
    await expect(createAuthApi(transport).logout()).rejects.toMatchObject({ code: 'INVALID_CSRF' })
    expect(transport).toHaveBeenCalledTimes(1)
  })

  it.each([400, 401, 403, 429, 503])('maps HTTP %s without echoing backend data or retrying', async status => {
    const transport = vi.fn<typeof fetch>().mockResolvedValue(json({ code: 'ERROR', message: 'secret server details' }, status))
    await expect(createAuthApi(transport).me()).rejects.toMatchObject({ status, message: expect.not.stringContaining('secret') })
    expect(transport).toHaveBeenCalledTimes(1)
  })

  it('reports uncertain delivery on network failure without replaying a mutation', async () => {
    const transport = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }))
      .mockRejectedValueOnce(new TypeError('network'))
    await expect(createAuthApi(transport).register(profile.email, 'long testing password', profile.nickname))
      .rejects.toMatchObject({ status: 0, message: expect.stringContaining('可能已被处理') })
    expect(transport).toHaveBeenCalledTimes(2)
  })

  it.each([null, { id: 123 }, { id: 'user', email: 'mail', nickname: '' }])('rejects invalid profiles: %j', async payload => {
    await expect(createAuthApi(vi.fn<typeof fetch>().mockResolvedValue(json(payload))).me()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('rejects HTML fallback and foreign capability responses', async () => {
    await expect(createAuthApi(vi.fn<typeof fetch>().mockResolvedValue(new Response('<html>'))).status()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    await expect(createAuthApi(vi.fn<typeof fetch>().mockResolvedValue(json({ loginAvailable: true, registrationAvailable: true }))).status()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('sends each account action to its real endpoint', async () => {
    const transport = vi.fn<typeof fetch>().mockImplementation(async path => path === '/api/auth/csrf'
      ? json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }) : new Response(null, { status: 204 }))
    const api = createAuthApi(transport)
    await api.register('a@example.com', 'testing password', 'name')
    await api.verify('token')
    await api.resend('a@example.com')
    await api.forgot('a@example.com')
    await api.reset('token', 'new testing password')
    expect(transport.mock.calls.filter(([, options]) => options?.method === 'POST').map(([path, options]) => [path, JSON.parse(options?.body as string)])).toEqual([
      ['/api/auth/register', { email: 'a@example.com', password: 'testing password', nickname: 'name' }],
      ['/api/auth/verify-email', { token: 'token' }], ['/api/auth/verification/request', { email: 'a@example.com' }],
      ['/api/auth/password/forgot', { email: 'a@example.com' }], ['/api/auth/password/reset', { token: 'token', password: 'new testing password' }],
    ])
  })

  it('validates game session client type and expiration format', async () => {
    const session = { userId: 'u', sessionId: 's', clientType: 'WEB', expiresAt: '2026-09-07T12:00:00Z' }
    expect(await createAuthApi(vi.fn<typeof fetch>().mockResolvedValue(json(session))).gameSession()).toEqual(session)
    await expect(createAuthApi(vi.fn<typeof fetch>().mockResolvedValue(json({ ...session, clientType: 'APP' }))).gameSession()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })
})
