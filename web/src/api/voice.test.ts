import { describe, expect, it, vi } from 'vitest'
import { createVoiceApi, VoiceError } from './voice'

describe('voice API', () => {
  it('sends only matchId with CSRF and rejects invalid grants', async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ url: 'ws://127.0.0.1:7880', token: 'a.b.c',
        expiresAt: '2026-09-24T10:00:00Z' })))
    const grant = await createVoiceApi(fetcher).token('match-id')
    expect(grant.url).toBe('ws://127.0.0.1:7880')
    expect(fetcher).toHaveBeenNthCalledWith(2, '/api/voice/token', expect.objectContaining({
      method: 'POST', credentials: 'same-origin', cache: 'no-store',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': 'csrf' },
      body: JSON.stringify({ matchId: 'match-id' }),
    }))
    const bad = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ url: 'https://wrong', token: 'a.b.c', expiresAt: 'bad' })))
    await expect(createVoiceApi(bad).token('match-id')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('returns a safe message on revoked membership', async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: 'VOICE_NOT_AVAILABLE' }), { status: 404 }))
    await expect(createVoiceApi(fetcher).token('match-id')).rejects.toEqual(expect.objectContaining({
      status: 404, code: 'VOICE_NOT_AVAILABLE', message: '当前对局没有可加入的队友语音。',
    } satisfies Partial<VoiceError>))
  })
})
