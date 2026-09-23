import { afterEach, describe, expect, it, vi } from 'vitest'
import { connectMatchSocket, type MatchSocketStatus } from './matchSocket'
import { snapshot } from '../test/matchFixture'

const matchId = '9c766d3d-7918-46e2-a96e-5967b70c13aa'

class FakeSocket {
  readyState: number = WebSocket.CONNECTING
  sent: string[] = []
  onopen: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onclose: ((event: { code: number; reason: string }) => void) | null = null
  onerror: (() => void) | null = null
  send(value: string) { this.sent.push(value) }
  close() { this.readyState = WebSocket.CLOSED }
  open() { this.readyState = WebSocket.OPEN; this.onopen?.() }
  message(value: unknown) { this.onmessage?.({ data: JSON.stringify(value) }) }
  disconnect(code = 1006, reason = '') { this.readyState = WebSocket.CLOSED; this.onclose?.({ code, reason }) }
}

afterEach(() => { vi.useRealTimers() })

describe('match WebSocket transport', () => {
  it('subscribes before sending, then delivers only this match and stops on cleanup', () => {
    const peer = new FakeSocket()
    const statuses: MatchSocketStatus[] = []
    const received: number[] = []
    const transport = connectMatchSocket(matchId, {
      status: value => statuses.push(value), snapshot: value => received.push(value.view.version),
      acknowledged: () => {}, rejected: () => {}, error: () => {},
    }, () => peer as unknown as WebSocket)
    const command = { protocolVersion: 1 as const, commandId: 'test', expectedVersion: 4, type: 'DRAW' as const }
    peer.open()
    expect(JSON.parse(peer.sent[0]!)).toMatchObject({ type: 'SUBSCRIBE', matchId })
    expect(transport.send(command)).toBe(false)
    peer.message({ protocolVersion: 1, type: 'MATCH_SNAPSHOT', matchId: 'other', ...snapshot })
    expect(received).toEqual([])
    peer.message({ protocolVersion: 1, type: 'MATCH_SNAPSHOT', matchId, ...snapshot })
    expect(statuses.at(-1)).toBe('connected')
    expect(transport.send(command)).toBe(true)
    expect(JSON.parse(peer.sent[1]!)).toMatchObject({ type: 'COMMAND', matchId, command })
    transport.close()
    peer.message({ protocolVersion: 1, type: 'MATCH_SNAPSHOT', matchId, ...snapshot })
    expect(received).toEqual([4])
  })

  it('reconnects and subscribes again without replaying an uncertain command', () => {
    vi.useFakeTimers()
    const peers: FakeSocket[] = []
    const statuses: MatchSocketStatus[] = []
    const transport = connectMatchSocket(matchId, {
      status: value => statuses.push(value), snapshot: () => {}, acknowledged: () => {},
      rejected: () => {}, error: () => {},
    }, () => { const peer = new FakeSocket(); peers.push(peer); return peer as unknown as WebSocket })
    peers[0]!.open()
    peers[0]!.message({ protocolVersion: 1, type: 'MATCH_SNAPSHOT', matchId, ...snapshot })
    expect(transport.send({ protocolVersion: 1, commandId: 'one', expectedVersion: 4, type: 'DRAW' })).toBe(true)
    peers[0]!.disconnect()
    expect(statuses.at(-1)).toBe('disconnected')
    vi.advanceTimersByTime(500)
    expect(peers).toHaveLength(2)
    peers[1]!.open()
    expect(peers[1]!.sent).toHaveLength(1)
    expect(JSON.parse(peers[1]!.sent[0]!)).toMatchObject({ type: 'SUBSCRIBE' })
    transport.close()
  })

  it('retries rate-limit policy closes but stops when the session is invalid', () => {
    vi.useFakeTimers()
    const peers: FakeSocket[] = []
    const statuses: MatchSocketStatus[] = []
    const transport = connectMatchSocket(matchId, {
      status: value => statuses.push(value), snapshot: () => {}, acknowledged: () => {},
      rejected: () => {}, error: () => {},
    }, () => { const peer = new FakeSocket(); peers.push(peer); return peer as unknown as WebSocket })
    peers[0]!.disconnect(1008, 'RATE_LIMITED')
    expect(statuses.at(-1)).toBe('disconnected')
    vi.advanceTimersByTime(500)
    expect(peers).toHaveLength(2)
    peers[1]!.disconnect(1008)
    expect(statuses.at(-1)).toBe('unauthorized')
    vi.advanceTimersByTime(10000)
    expect(peers).toHaveLength(2)
    transport.close()
  })
})
