import { parseChatItem, type ChatItem } from './chat'

export type ChatSocketHandlers = {
  subscribed: () => void
  message: (item: ChatItem) => void
  disconnected: () => void
}

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)

export function connectChatSocket(roomId: string, handlers: ChatSocketHandlers,
  createSocket: (url: string) => WebSocket = url => new WebSocket(url)) {
  let socket: WebSocket | null = null
  let retry: ReturnType<typeof setTimeout> | undefined
  let stopped = false
  let attempts = 0
  let generation = 0

  function scheduleRetry() {
    if (stopped) return
    handlers.disconnected()
    clearTimeout(retry)
    retry = setTimeout(connect, Math.min(5000, 500 * 2 ** Math.min(attempts++, 4)))
  }

  function connect() {
    if (stopped) return
    const current = ++generation
    const url = `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws/chat`
    let peer: WebSocket
    try { peer = createSocket(url) }
    catch { scheduleRetry(); return }
    socket = peer
    peer.onopen = () => {
      if (stopped || current !== generation) return
      attempts = 0
      try { peer.send(JSON.stringify({ protocolVersion: 1, type: 'SUBSCRIBE', roomId })) }
      catch { peer.close(); scheduleRetry() }
    }
    peer.onmessage = event => {
      if (stopped || current !== generation) return
      try {
        const envelope: unknown = JSON.parse(String(event.data))
        if (!record(envelope) || envelope.protocolVersion !== 1 || envelope.roomId !== roomId) return
        if (envelope.type === 'CHAT_SUBSCRIBED') handlers.subscribed()
        else if (envelope.type === 'CHAT_MESSAGE') {
          const item = parseChatItem(envelope.item)
          if (item.roomId === roomId) handlers.message(item)
        }
      } catch { handlers.disconnected() }
    }
    peer.onclose = event => {
      if (stopped || current !== generation) return
      socket = null
      if (event.code === 1008 && event.reason !== 'RATE_LIMITED') {
        handlers.disconnected()
        return
      }
      scheduleRetry()
    }
    peer.onerror = () => { if (!stopped && current === generation) handlers.disconnected() }
  }

  if (typeof WebSocket !== 'undefined') connect()
  return {
    close() {
      stopped = true
      generation++
      clearTimeout(retry)
      socket?.close()
      socket = null
    },
  }
}
