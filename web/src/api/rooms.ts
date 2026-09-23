export type RoomMode = 'CLASSIC' | 'TEAM_2V2'
export type RoomMember = { userId: string; nickname: string; seat: number; team: 'A' | 'B' | null; ready: boolean }
export type Room = {
  id: string; code: string; mode: RoomMode; maxPlayers: number; hostUserId: string
  state: 'WAITING' | 'STARTING' | 'PLAYING'; version: number; expiresAt: string
  canStart: boolean; members: RoomMember[]
}

export class RoomError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message)
    this.name = 'RoomError'
  }
}

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const inviteCode = /^[A-HJ-NP-Z2-9]{10}$/
const object = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value)

function parseRoom(value: unknown): Room {
  if (!object(value) || !uuid.test(String(value.id)) || !inviteCode.test(String(value.code))
    || !['CLASSIC', 'TEAM_2V2'].includes(String(value.mode)) || !Number.isInteger(value.maxPlayers)
    || !uuid.test(String(value.hostUserId)) || !['WAITING', 'STARTING', 'PLAYING'].includes(String(value.state))
    || !Number.isInteger(value.version) || typeof value.expiresAt !== 'string' || !Number.isFinite(Date.parse(value.expiresAt))
    || typeof value.canStart !== 'boolean' || !Array.isArray(value.members)) {
    throw new RoomError(502, 'INVALID_RESPONSE', '房间服务返回异常，请稍后重试。')
  }
  const members = value.members.map(member => {
    if (!object(member) || !uuid.test(String(member.userId)) || typeof member.nickname !== 'string'
      || !Number.isInteger(member.seat) || !(member.team === null || member.team === 'A' || member.team === 'B')
      || typeof member.ready !== 'boolean') throw new RoomError(502, 'INVALID_RESPONSE', '房间成员信息异常，请稍后重试。')
    return member as RoomMember
  })
  return { ...value, members } as Room
}

export function roomErrorMessage(error: unknown): string {
  return error instanceof RoomError ? error.message : '房间连接失败，请检查网络后重试。'
}

export function createRoomApi(fetcher: typeof fetch = (...args) => fetch(...args)) {
  async function request(path: string, init: RequestInit = {}): Promise<unknown> {
    let response: Response
    try {
      response = await fetcher(path, {
        ...init, credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(12000),
      })
    } catch {
      throw new RoomError(0, 'NETWORK_ERROR', '连接失败或超时；如果刚才提交了操作，请先刷新房间状态。')
    }
    if (!response.ok) {
      const payload: unknown = await response.json().catch(() => null)
      const code = object(payload) && typeof payload.code === 'string' ? payload.code : 'REQUEST_FAILED'
      const known = object(payload) && typeof payload.message === 'string' && /^[\u3400-\u9fff\u3000-\u303f，。；：！、？\s\dA-Za-z]{1,80}$/.test(payload.message)
      const message = response.status === 401 ? '登录已失效，请重新登录。'
        : response.status === 403 && code !== 'ROOM_FORBIDDEN' ? '安全校验未通过，请刷新页面重试。'
          : response.status >= 500 ? '房间服务暂不可用，请稍后重试。'
            : known ? (payload as { message: string }).message : '房间操作失败，请刷新后重试。'
      throw new RoomError(response.status, code, message)
    }
    if (response.status === 204) return null
    try { return await response.json() } catch { throw new RoomError(502, 'INVALID_RESPONSE', '房间服务返回异常，请稍后重试。') }
  }

  async function mutate(path: string, body?: unknown) {
    const csrf = await request('/api/auth/csrf')
    if (!object(csrf) || csrf.headerName !== 'X-CSRF-TOKEN' || typeof csrf.token !== 'string' || !csrf.token) {
      throw new RoomError(502, 'INVALID_CSRF', '无法取得安全凭证，请刷新页面重试。')
    }
    return request(path, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf.token },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  }

  return {
    async current(): Promise<Room | null> { const result = await request('/api/rooms/current'); return result === null ? null : parseRoom(result) },
    async get(id: string): Promise<Room> { return parseRoom(await request(`/api/rooms/${id}`)) },
    async create(mode: RoomMode, maxPlayers: number): Promise<Room> { return parseRoom(await mutate('/api/rooms', { mode, maxPlayers })) },
    async join(code: string): Promise<Room> { return parseRoom(await mutate('/api/rooms/join', { code })) },
    async leave(id: string): Promise<void> { await mutate(`/api/rooms/${id}/leave`) },
    async ready(room: Room, ready: boolean): Promise<Room> { return parseRoom(await mutate(`/api/rooms/${room.id}/ready`, { ready, expectedVersion: room.version })) },
    async team(room: Room, team: 'A' | 'B'): Promise<Room> { return parseRoom(await mutate(`/api/rooms/${room.id}/team`, { team, expectedVersion: room.version })) },
    async settings(room: Room, maxPlayers: number): Promise<Room> { return parseRoom(await mutate(`/api/rooms/${room.id}/settings`, { maxPlayers, expectedVersion: room.version })) },
  }
}

export const roomApi = createRoomApi()
