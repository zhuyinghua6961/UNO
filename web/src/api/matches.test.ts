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

  it('loads only the authenticated history page with an opaque cursor', async () => {
    const page = { items: [{ matchId, mode: 'CLASSIC', endedAt: '2026-09-23T08:00:00Z',
      rounds: 3, winnerUserId: matchId, result: 'WIN',
      players: [{ userId: matchId, seat: 0, nickname: 'Alice', score: 500 }] }], nextCursor: null }
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify(page), { status: 200 }))
    expect((await createMatchApi(fetcher).history('next|page')).items[0]?.result).toBe('WIN')
    expect(fetcher.mock.calls[0]).toMatchObject(['/api/matches/history?cursor=next%7Cpage',
      { credentials: 'same-origin', cache: 'no-store' }])
  })

  it('accepts team results in the same private history contract', async () => {
    const item = { matchId, mode: 'TEAM_2V2', endedAt: '2026-09-24T08:00:00Z',
      rounds: 1, winnerUserId: matchId, result: 'WIN',
      players: [{ userId: matchId, seat: 0, nickname: 'Alice', score: 27 }] }
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({ items: [item], nextCursor: null }), { status: 200 }))
    expect((await createMatchApi(fetcher).history()).items[0]).toMatchObject({ mode: 'TEAM_2V2', result: 'WIN' })
  })

  it('parses interrupted matches without inventing a winner', async () => {
    const item = { matchId, mode: 'CLASSIC', endedAt: '2026-09-24T08:00:00Z',
      rounds: 2, winnerUserId: null, result: 'INTERRUPTED',
      players: [{ userId: matchId, seat: 0, nickname: 'Alice', score: 14 }] }
    const api = createMatchApi(vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({
      items: [item], nextCursor: null,
    }), { status: 200 })))
    expect((await api.history()).items[0]).toMatchObject({ result: 'INTERRUPTED', winnerUserId: null })
    expect(() => parseMatchSnapshot({ ...snapshot, status: 'unknown' })).toThrow(MatchError)
  })
})
