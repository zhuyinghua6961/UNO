import { describe, expect, it, vi } from 'vitest'
import { ChatError, createChatApi, type ChatItem } from './chat'

const roomId = 'cae648d7-afaf-4dcd-a492-8bad87cb8c86'
const message: ChatItem = {
  id: 'e53572ef-9237-425b-b45a-2d14a017a74c', roomId, channel: 'ROOM', sequence: 3,
  senderUserId: 'c4d75d7d-117c-45e3-a289-8e38a2fed8cc', senderNickname: 'Alice',
  clientMessageId: '4904abda-1a52-49f8-9385-926e36493996', content: '<b>纯文字</b>',
  createdAt: '2026-09-23T10:00:00Z',
}

describe('room chat HTTP contract', () => {
  it('sends a stable client message id with Web CSRF and parses the server identity', async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify(message), { status: 200 }))
    const saved = await createChatApi(fetcher).send(roomId, message.clientMessageId, message.content)
    expect(saved.senderUserId).toBe(message.senderUserId)
    expect(saved.content).toBe('<b>纯文字</b>')
    expect(fetcher.mock.calls[1]).toMatchObject([
      `/api/rooms/${roomId}/messages`,
      { method: 'POST', credentials: 'same-origin', headers: { 'X-CSRF-TOKEN': 'csrf' },
        body: JSON.stringify({ clientMessageId: message.clientMessageId, content: message.content }) },
    ])
  })

  it('uses the cursor after the recent page and rejects malformed messages', async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify({ items: [message], nextSequence: 3, hasMore: false }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ items: [{ ...message, sequence: '3' }], nextSequence: 3, hasMore: false }), { status: 200 }))
    const api = createChatApi(fetcher)
    expect((await api.history(roomId, 0, true)).nextSequence).toBe(3)
    expect(fetcher.mock.calls[0][0]).toContain('latest=true')
    await expect(api.history(roomId, 3)).rejects.toBeInstanceOf(ChatError)
    expect(fetcher.mock.calls[1][0]).toContain('after=3')
  })

  it('selects team scope without allowing a forged recipient list', async () => {
    const team = { ...message, channel: 'TEAM_A' }
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify({ items: [team], nextSequence: 3, hasMore: false }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify(team), { status: 200 }))
    const api = createChatApi(fetcher)
    expect((await api.history(roomId, 0, true, 'TEAM')).items[0]?.channel).toBe('TEAM_A')
    expect(fetcher.mock.calls[0][0]).toContain('channel=TEAM')
    await api.send(roomId, message.clientMessageId, message.content, 'TEAM')
    expect(fetcher.mock.calls[2][1]?.body).toBe(JSON.stringify({
      clientMessageId: message.clientMessageId, content: message.content, channel: 'TEAM',
    }))
  })
})
