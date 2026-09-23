import { parseMatchReceipt, parseMatchSnapshot, type MatchCommand, type MatchReceipt, type MatchSnapshot } from '../api/matches'

export type MatchSocketStatus = 'connecting' | 'connected' | 'disconnected' | 'unauthorized'
export type MatchSocketHandlers = {
  status: (status: MatchSocketStatus) => void
  snapshot: (snapshot: MatchSnapshot) => void
  acknowledged: (receipt: MatchReceipt) => void
  rejected: (commandId: string, code: string) => void
  error: (message: string) => void
}

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)

export function connectMatchSocket(matchId: string, handlers: MatchSocketHandlers,
  createSocket: (url: string) => WebSocket = url => new WebSocket(url)) {
  let socket: WebSocket | null = null
  let retry: ReturnType<typeof setTimeout> | undefined
  let stopped = false
  let attempts = 0
  let generation = 0
  let subscribed = false

  function connect() {
    if (stopped) return
    const current = ++generation
    subscribed = false
    handlers.status('connecting')
    const url = `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws/game`
    let peer: WebSocket
    try { peer = createSocket(url) }
    catch { scheduleRetry(); return }
    socket = peer
    peer.onopen = () => {
      if (stopped || current !== generation) return
      attempts = 0
      peer.send(JSON.stringify({ protocolVersion: 1, type: 'SUBSCRIBE', matchId }))
    }
    peer.onmessage = event => {
      if (stopped || current !== generation) return
      try {
        const message: unknown = JSON.parse(String(event.data))
        if (!record(message) || message.protocolVersion !== 1) throw new Error('Invalid envelope')
        if (message.type === 'MATCH_SNAPSHOT' && message.matchId === matchId) {
          handlers.snapshot(parseMatchSnapshot(message))
          subscribed = true
          handlers.status('connected')
        } else if (message.type === 'COMMAND_ACK' && message.matchId === matchId) {
          handlers.acknowledged(parseMatchReceipt(message.result))
        } else if (message.type === 'COMMAND_REJECTED' && message.matchId === matchId
          && typeof message.commandId === 'string' && typeof message.code === 'string') {
          handlers.rejected(message.commandId, message.code)
        } else if (message.type === 'ERROR' && typeof message.code === 'string') {
          handlers.error(`对局连接返回 ${message.code}，请同步状态。`)
        } else throw new Error('Unexpected envelope')
      } catch { handlers.error('收到无效的对局消息，请同步状态。') }
    }
    peer.onclose = event => {
      if (stopped || current !== generation) return
      socket = null
      subscribed = false
      if (event.code === 1008 && event.reason !== 'RATE_LIMITED') {
        handlers.status('unauthorized')
        return
      }
      scheduleRetry()
    }
    peer.onerror = () => { if (!stopped && current === generation) handlers.status('disconnected') }
  }

  function scheduleRetry() {
    if (stopped) return
    handlers.status('disconnected')
    clearTimeout(retry)
    const delay = Math.min(5000, 500 * 2 ** Math.min(attempts++, 4))
    retry = setTimeout(connect, delay)
  }

  connect()
  return {
    send(command: MatchCommand): boolean {
      if (!socket || socket.readyState !== WebSocket.OPEN || !subscribed) return false
      socket.send(JSON.stringify({ protocolVersion: 1, type: 'COMMAND', matchId, command }))
      return true
    },
    close() {
      stopped = true
      generation++
      subscribed = false
      clearTimeout(retry)
      socket?.close()
      socket = null
    },
  }
}
