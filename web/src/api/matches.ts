export type CardColor = 'RED' | 'YELLOW' | 'GREEN' | 'BLUE'
export type CardKind = 'NUMBER' | 'SKIP' | 'REVERSE' | 'DRAW_TWO' | 'WILD' | 'WILD_DRAW_FOUR'
export type Card = { id: number; color: CardColor | null; kind: CardKind; number: number }
export type MatchPhase = 'INITIAL_WILD_COLOR' | 'TURN' | 'AFTER_DRAW' | 'DRAW_FOUR_RESPONSE' | 'ROUND_OVER' | 'MATCH_OVER'
export type MatchPlayer = { userId: string; seat: number; handCount: number; score: number }
export type MatchView = {
  rulesVersion: number; version: number; roundNumber: number; phase: MatchPhase
  currentSeat: number; direction: number; topCard: Card; activeColor: CardColor | null
  drawCount: number; discardCount: number; ownHand: Card[]; players: MatchPlayer[]
  unoVulnerableSeat: number | null; roundWinnerSeat: number | null; roundPoints: number
  canRespondToDrawFour: boolean; drawnCardId: number | null
}
export type MatchSnapshot = { view: MatchView; deadlineAt: string | null; status: 'PLAYING' | 'ENDED' | 'INTERRUPTED' }
export type MatchStart = MatchSnapshot & { matchId: string; roomVersion: number }
export type MatchCommandType = 'PLAY' | 'DRAW' | 'PASS' | 'SAY_UNO' | 'CATCH_UNO'
  | 'ACCEPT_DRAW_FOUR' | 'CHALLENGE_DRAW_FOUR' | 'CHOOSE_INITIAL_COLOR' | 'NEXT_ROUND'
export type MatchCommand = {
  protocolVersion: 1; commandId: string; expectedVersion: number; type: MatchCommandType
  cardId?: number; chosenColor?: CardColor; targetUserId?: string; callUno?: boolean
}
export type MatchReceipt = MatchSnapshot & {
  commandId: string; duplicate: boolean; appliedVersion: number; event: string
  challengeOutcome: string; cardsDrawnBySeat: Record<string, number>; privateChallengeEvidence: Card[]
}
export type HistoryPlayer = { userId: string; seat: number; nickname: string | null; score: number }
export type HistoryItem = { matchId: string; mode: 'CLASSIC' | 'TEAM_2V2'; endedAt: string; rounds: number;
  winnerUserId: string | null; result: 'WIN' | 'LOSS' | 'INTERRUPTED'; players: HistoryPlayer[] }
export type HistoryPage = { items: HistoryItem[]; nextCursor: string | null }

export class MatchError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message); this.name = 'MatchError' }
}

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const colors = ['RED', 'YELLOW', 'GREEN', 'BLUE']
const kinds = ['NUMBER', 'SKIP', 'REVERSE', 'DRAW_TWO', 'WILD', 'WILD_DRAW_FOUR']
const phases = ['INITIAL_WILD_COLOR', 'TURN', 'AFTER_DRAW', 'DRAW_FOUR_RESPONSE', 'ROUND_OVER', 'MATCH_OVER']
const record = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value)
const integer = (value: unknown): value is number => Number.isInteger(value)
const nullableInteger = (value: unknown) => value === null || integer(value)
const nullableColor = (value: unknown) => value === null || colors.includes(String(value))
const deadline = (value: unknown) => value === null || (typeof value === 'string' && Number.isFinite(Date.parse(value)))
const invalid = () => new MatchError(502, 'INVALID_RESPONSE', '对局服务返回异常，请刷新后重试。')

export function parseCard(value: unknown): Card {
  if (!record(value) || !integer(value.id) || value.id < 0 || value.id > 107
    || !nullableColor(value.color) || !kinds.includes(String(value.kind)) || !integer(value.number)) throw invalid()
  return value as Card
}

export function parseMatchSnapshot(value: unknown): MatchSnapshot {
  if (!record(value) || !record(value.view) || !deadline(value.deadlineAt)
    || !['PLAYING', 'ENDED', 'INTERRUPTED'].includes(String(value.status))) throw invalid()
  const view = value.view
  if (!integer(view.rulesVersion) || view.rulesVersion !== 1 || !integer(view.version) || view.version < 1
    || !integer(view.roundNumber) || !phases.includes(String(view.phase)) || !integer(view.currentSeat)
    || !integer(view.direction) || !nullableColor(view.activeColor) || !integer(view.drawCount)
    || !integer(view.discardCount) || !Array.isArray(view.ownHand) || !Array.isArray(view.players)
    || !nullableInteger(view.unoVulnerableSeat) || !nullableInteger(view.roundWinnerSeat)
    || !integer(view.roundPoints) || typeof view.canRespondToDrawFour !== 'boolean'
    || !nullableInteger(view.drawnCardId)) throw invalid()
  const players = view.players.map(player => {
    if (!record(player) || !uuid.test(String(player.userId)) || !integer(player.seat)
      || !integer(player.handCount) || !integer(player.score)) throw invalid()
    return player as MatchPlayer
  })
  if (view.currentSeat < 0 || view.currentSeat >= players.length) throw invalid()
  return { view: { ...view, topCard: parseCard(view.topCard), ownHand: view.ownHand.map(parseCard), players } as MatchView,
    deadlineAt: value.deadlineAt as string | null, status: value.status as MatchSnapshot['status'] }
}

export function parseMatchStart(value: unknown): MatchStart {
  if (!record(value) || !uuid.test(String(value.matchId)) || !integer(value.roomVersion)) throw invalid()
  return { ...parseMatchSnapshot(value), matchId: value.matchId as string, roomVersion: value.roomVersion as number }
}

export function parseMatchReceipt(value: unknown): MatchReceipt {
  if (!record(value) || !uuid.test(String(value.commandId)) || typeof value.duplicate !== 'boolean'
    || !integer(value.appliedVersion) || typeof value.event !== 'string'
    || typeof value.challengeOutcome !== 'string' || !record(value.cardsDrawnBySeat)
    || !Array.isArray(value.privateChallengeEvidence)) throw invalid()
  return { ...parseMatchSnapshot(value), commandId: value.commandId as string,
    duplicate: value.duplicate as boolean, appliedVersion: value.appliedVersion as number,
    event: value.event as string, challengeOutcome: value.challengeOutcome as string,
    cardsDrawnBySeat: value.cardsDrawnBySeat as Record<string, number>,
    privateChallengeEvidence: value.privateChallengeEvidence.map(parseCard) }
}

export function parseHistoryPage(value: unknown): HistoryPage {
  if (!record(value) || !Array.isArray(value.items)
    || !(value.nextCursor === null || typeof value.nextCursor === 'string')) throw invalid()
  const items = value.items.map(item => {
    if (!record(item) || !uuid.test(String(item.matchId)) || !['CLASSIC', 'TEAM_2V2'].includes(String(item.mode))
      || typeof item.endedAt !== 'string' || !Number.isFinite(Date.parse(item.endedAt))
      || !integer(item.rounds) || !['WIN', 'LOSS', 'INTERRUPTED'].includes(String(item.result))
      || (item.result === 'INTERRUPTED' ? item.winnerUserId !== null : !uuid.test(String(item.winnerUserId)))
      || !Array.isArray(item.players)) throw invalid()
    const players = item.players.map(player => {
      if (!record(player) || !uuid.test(String(player.userId)) || !integer(player.seat)
        || !(player.nickname === null || typeof player.nickname === 'string')
        || !integer(player.score)) throw invalid()
      return player as HistoryPlayer
    })
    return { ...item, players } as HistoryItem
  })
  return { items, nextCursor: value.nextCursor as string | null }
}

export function matchErrorMessage(error: unknown): string {
  if (!(error instanceof MatchError)) return '对局连接失败，请检查网络后重试。'
  if (error.code === 'TURN_EXPIRED') return '操作窗口已结束，正在同步服务器裁决。'
  if (error.code === 'MATCH_CONFLICT') return '牌局已变化，正在同步最新状态。'
  if (error.status === 401) return '登录已失效，请重新登录。'
  return error.message
}

export function createMatchApi(fetcher: typeof fetch = (...args) => fetch(...args)) {
  async function request(path: string, init: RequestInit = {}): Promise<unknown> {
    let response: Response
    try {
      response = await fetcher(path, {
        ...init, credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(12000),
      })
    } catch { throw new MatchError(0, 'NETWORK_ERROR', '连接失败或超时；如果已提交操作，请先同步对局状态。') }
    if (!response.ok) {
      const payload: unknown = await response.json().catch(() => null)
      const code = record(payload) && typeof payload.code === 'string' ? payload.code : 'REQUEST_FAILED'
      const message = response.status === 401 ? '登录已失效，请重新登录。'
        : response.status === 403 ? '安全校验未通过，请刷新页面重试。'
          : response.status >= 500 ? '对局服务暂不可用，请稍后重试。'
            : record(payload) && typeof payload.message === 'string' ? payload.message : '对局操作失败，请同步后重试。'
      throw new MatchError(response.status, code, message)
    }
    if (response.status === 204) return null
    try { return await response.json() } catch { throw invalid() }
  }

  async function mutate(path: string, body: unknown): Promise<unknown> {
    const csrf = await request('/api/auth/csrf')
    if (!record(csrf) || csrf.headerName !== 'X-CSRF-TOKEN' || typeof csrf.token !== 'string' || !csrf.token)
      throw new MatchError(502, 'INVALID_CSRF', '无法取得安全凭证，请刷新页面重试。')
    return request(path, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf.token },
      body: JSON.stringify(body) })
  }

  return {
    async start(roomId: string, expectedVersion: number): Promise<MatchStart> {
      return parseMatchStart(await mutate(`/api/rooms/${roomId}/start`, { expectedVersion }))
    },
    async current(roomId: string): Promise<MatchStart | null> {
      const value = await request(`/api/rooms/${roomId}/match`)
      return value === null ? null : parseMatchStart(value)
    },
    async state(matchId: string): Promise<MatchSnapshot> {
      return parseMatchSnapshot(await request(`/api/matches/${matchId}/state`))
    },
    async history(cursor?: string): Promise<HistoryPage> {
      const query = cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''
      return parseHistoryPage(await request(`/api/matches/history${query}`))
    },
  }
}

export const matchApi = createMatchApi()
