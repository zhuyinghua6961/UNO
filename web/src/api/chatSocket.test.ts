import { afterEach, describe, expect, it, vi } from 'vitest'
import { connectChatSocket } from './chatSocket'

const roomId = 'cae648d7-afaf-4dcd-a492-8bad87cb8c86'
const item = {
  id: 'e53572ef-9237-425b-b45a-000000000001', roomId, channel: 'TEAM_A', sequence: 1,
  senderUserId: 'c4d75d7d-117c-45e3-a289-8e38a2fed8cc', senderNickname: 'Alice',
  clientMessageId: '4904abda-1a52-49f8-9385-000000000001', content: '<script>text</script>',
  createdAt: '2026-09-24T10:00:00Z',
}

class Peer {
  onopen: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onclose: ((event: { code: number; reason: string }) => void) | null = null
  onerror: (() => void) | null = null
  sent: string[] = []
  closed = false
  send(value: string) { this.sent.push(value) }
  close() { this.closed = true }
  message(value: unknown) { this.onmessage?.({ data: JSON.stringify(value) }) }
}

afterEach(() => { vi.useRealTimers() })

describe('chat push subscription', () => {
  it('subscribes only to the requested room and parses authorized chat events', () => {
    const peer = new Peer()
    const subscribed = vi.fn()
    const message = vi.fn()
    const disconnected = vi.fn()
    const connection = connectChatSocket(roomId, { subscribed, message, disconnected }, () => peer as unknown as WebSocket)
    peer.onopen?.()
    expect(JSON.parse(peer.sent[0]!)).toEqual({ protocolVersion: 1, type: 'SUBSCRIBE', roomId })
    peer.message({ protocolVersion: 1, type: 'CHAT_SUBSCRIBED', roomId })
    peer.message({ protocolVersion: 1, type: 'CHAT_MESSAGE', roomId, item })
    peer.message({ protocolVersion: 1, type: 'CHAT_MESSAGE', roomId: 'other', item })
    peer.message({ protocolVersion: 1, type: 'CHAT_MESSAGE', roomId, item: { ...item, id: 'invalid' } })
    expect(subscribed).toHaveBeenCalledTimes(1)
    expect(message).toHaveBeenCalledOnce()
    expect(message).toHaveBeenCalledWith(item)
    expect(disconnected).toHaveBeenCalledOnce()
    connection.close()
    expect(peer.closed).toBe(true)
  })

  it('reconnects after a dropped transport and stops on explicit close', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const peers: Peer[] = []
    const disconnected = vi.fn()
    const connection = connectChatSocket(roomId, { subscribed: vi.fn(), message: vi.fn(), disconnected }, () => {
      const peer = new Peer()
      peers.push(peer)
      return peer as unknown as WebSocket
    })
    peers[0]!.onclose?.({ code: 1006, reason: '' })
    expect(disconnected).toHaveBeenCalledOnce()
    await vi.advanceTimersByTimeAsync(500)
    expect(peers).toHaveLength(2)
    connection.close()
    peers[1]!.onclose?.({ code: 1006, reason: '' })
    await vi.advanceTimersByTimeAsync(5000)
    expect(peers).toHaveLength(2)
  })
})
