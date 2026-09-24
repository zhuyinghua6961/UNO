export type ChatItem = {
  id: string; roomId: string; channel: 'ROOM' | 'TEAM_A' | 'TEAM_B'; sequence: number
  senderUserId: string; senderNickname: string; clientMessageId: string
  content: string; createdAt: string
  redacted?: boolean
}
export type ChatPage = { items: ChatItem[]; nextSequence: number; hasMore: boolean }
export type ChatScope = 'ROOM' | 'TEAM'
export type ChatReportReason = 'SPAM' | 'ABUSE' | 'OTHER'

export class ChatError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message)
    this.name = 'ChatError'
  }
}

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const object = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
const invalid = () => new ChatError(502, 'INVALID_RESPONSE', '消息服务返回异常，请稍后重试。')

export function parseChatItem(value: unknown): ChatItem {
  if (!object(value) || !uuid.test(String(value.id)) || !uuid.test(String(value.roomId))
    || !['ROOM', 'TEAM_A', 'TEAM_B'].includes(String(value.channel))
    || !Number.isSafeInteger(value.sequence) || (value.sequence as number) < 1
    || !uuid.test(String(value.senderUserId)) || !uuid.test(String(value.clientMessageId))
    || typeof value.senderNickname !== 'string' || typeof value.content !== 'string'
    || typeof value.createdAt !== 'string' || !Number.isFinite(Date.parse(value.createdAt))
    || (value.redacted !== undefined && typeof value.redacted !== 'boolean')) throw invalid()
  return value as ChatItem
}

function page(value: unknown): ChatPage {
  if (!object(value) || !Array.isArray(value.items) || !Number.isSafeInteger(value.nextSequence)
    || (value.nextSequence as number) < 0 || typeof value.hasMore !== 'boolean') throw invalid()
  const items = value.items.map(parseChatItem)
  if (items.some((message, index) => index > 0 && message.sequence <= items[index - 1]!.sequence)) throw invalid()
  return { items, nextSequence: value.nextSequence as number, hasMore: value.hasMore as boolean }
}

export function chatErrorMessage(error: unknown): string {
  if (!(error instanceof ChatError)) return '消息连接失败，请检查网络后重试。'
  if (error.status === 401) return '登录已失效，请重新登录。'
  if (error.status === 429) return '发送过快，请稍后重试。'
  return error.message
}

export function createChatApi(fetcher: typeof fetch = (...args) => fetch(...args)) {
  async function request(path: string, init: RequestInit = {}): Promise<unknown> {
    let response: Response
    try {
      response = await fetcher(path, {
        ...init, credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(12000),
      })
    } catch { throw new ChatError(0, 'NETWORK_ERROR', '消息连接失败或超时；发送状态可能未确认，请重试同一消息。') }
    if (!response.ok) {
      const payload: unknown = await response.json().catch(() => null)
      const code = object(payload) && typeof payload.code === 'string' ? payload.code : 'REQUEST_FAILED'
      const message = response.status === 401 ? '登录已失效，请重新登录。'
        : code === 'CHAT_MUTED' ? '当前账号暂不能发送文字消息。'
          : response.status === 403 ? '安全校验未通过，请刷新页面重试。'
          : response.status >= 500 ? '消息服务暂不可用，请稍后重试。'
            : object(payload) && typeof payload.message === 'string' ? payload.message : '消息操作失败，请稍后重试。'
      throw new ChatError(response.status, code, message)
    }
    try { return await response.json() } catch { throw invalid() }
  }

  return {
    async history(roomId: string, after = 0, latest = false, scope: ChatScope = 'ROOM', limit = 50): Promise<ChatPage> {
      const query = new URLSearchParams({ after: String(after), limit: String(limit), latest: String(latest) })
      if (scope === 'TEAM') query.set('channel', 'TEAM')
      const result = page(await request(`/api/rooms/${roomId}/messages?${query}`))
      if (result.items.some(message => scope === 'ROOM' ? message.channel !== 'ROOM' : message.channel === 'ROOM'))
        throw invalid()
      return result
    },
    async send(roomId: string, clientMessageId: string, content: string, scope: ChatScope = 'ROOM'): Promise<ChatItem> {
      const csrf = await request('/api/auth/csrf')
      if (!object(csrf) || csrf.headerName !== 'X-CSRF-TOKEN' || typeof csrf.token !== 'string' || !csrf.token)
        throw new ChatError(502, 'INVALID_CSRF', '无法取得安全凭证，请刷新页面重试。')
      const saved = parseChatItem(await request(`/api/rooms/${roomId}/messages`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf.token },
        body: JSON.stringify({ clientMessageId, content, ...(scope === 'TEAM' ? { channel: 'TEAM' } : {}) }),
      }))
      if (scope === 'ROOM' ? saved.channel !== 'ROOM' : saved.channel === 'ROOM') throw invalid()
      return saved
    },
    async report(roomId: string, messageId: string, reason: ChatReportReason): Promise<string> {
      const csrf = await request('/api/auth/csrf')
      if (!object(csrf) || csrf.headerName !== 'X-CSRF-TOKEN' || typeof csrf.token !== 'string' || !csrf.token)
        throw new ChatError(502, 'INVALID_CSRF', '无法取得安全凭证，请刷新页面重试。')
      const receipt = await request(`/api/rooms/${roomId}/messages/${messageId}/reports`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf.token },
        body: JSON.stringify({ reason }),
      })
      if (!object(receipt) || !uuid.test(String(receipt.id)) || !['OPEN', 'RESOLVED'].includes(String(receipt.status)))
        throw invalid()
      return receipt.id as string
    },
  }
}

export const chatApi = createChatApi()
