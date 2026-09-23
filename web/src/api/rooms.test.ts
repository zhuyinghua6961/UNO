import { describe, expect, it, vi } from 'vitest'
import { createRoomApi, RoomError, type Room } from './rooms'

const id = 'cae648d7-afaf-4dcd-a492-8bad87cb8c86'
const member = 'c4d75d7d-117c-45e3-a289-8e38a2fed8cc'
const room: Room = {
  id, code: 'ABCDEFGHJK', mode: 'CLASSIC', maxPlayers: 4, hostUserId: member,
  state: 'WAITING', version: 1, expiresAt: '2026-09-24T12:00:00Z', canStart: false,
  members: [{ userId: member, nickname: 'Alice', seat: 0, team: null, ready: false }],
}

describe('room HTTP contract', () => {
  it('uses same-origin CSRF for a Web mutation and sends the server version', async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify(room), { status: 200 }))
    const result = await createRoomApi(fetcher).ready(room, true)
    expect(result.id).toBe(id)
    expect(fetcher).toHaveBeenCalledTimes(2)
    expect(fetcher.mock.calls[1][0]).toBe(`/api/rooms/${id}/ready`)
    expect(fetcher.mock.calls[1][1]).toMatchObject({ method: 'POST', credentials: 'same-origin', headers: { 'X-CSRF-TOKEN': 'csrf' }, body: JSON.stringify({ ready: true, expectedVersion: 1 }) })
  })

  it('keeps an empty current room distinct from a failed request', async () => {
    const api = createRoomApi(vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 204 })))
    expect(await api.current()).toBeNull()
  })

  it('does not retry a timed out create request', async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }), { status: 200 }))
      .mockRejectedValueOnce(new Error('timeout'))
    await expect(createRoomApi(fetcher).create('CLASSIC', 4)).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('rejects malformed room responses', async () => {
    const api = createRoomApi(vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({ ...room, members: [{ ...room.members[0], ready: 'true' }] }), { status: 200 })))
    await expect(api.get(id)).rejects.toBeInstanceOf(RoomError)
  })
})
