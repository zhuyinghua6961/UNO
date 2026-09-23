import { describe, expect, it, vi } from 'vitest'
import { createMatchApi, MatchError, parseMatchSnapshot } from './matches'
import { matchId, snapshot } from '../test/matchFixture'

const roomId = 'cae648d7-afaf-4dcd-a492-8bad87cb8c86'

describe('match HTTP contract', () => {
  it('sends a versioned start with same-origin CSRF', async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ ...snapshot, matchId, roomVersion: 8 }), { status: 200 }))
    const started = await createMatchApi(fetcher).start(roomId, 7)
    expect(started.matchId).toBe(matchId)
    expect(fetcher.mock.calls[1]).toMatchObject([`/api/rooms/${roomId}/start`, {
      method: 'POST', credentials: 'same-origin', body: JSON.stringify({ expectedVersion: 7 }),
      headers: { 'X-CSRF-TOKEN': 'csrf' },
    }])
  })

  it('keeps no active match distinct from a failed response', async () => {
    const api = createMatchApi(vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 204 })))
    expect(await api.current(roomId)).toBeNull()
  })

  it('rejects malformed personal views', () => {
    expect(() => parseMatchSnapshot({ ...snapshot, view: { ...snapshot.view, ownHand: null } })).toThrow(MatchError)
    expect(() => parseMatchSnapshot({ ...snapshot, view: { ...snapshot.view, players: [{ userId: 'forged' }] } })).toThrow(MatchError)
    expect(parseMatchSnapshot(snapshot).view.ownHand[0]?.id).toBe(2)
  })
})
