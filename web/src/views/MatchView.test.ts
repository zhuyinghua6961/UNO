import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { matchApi, parseMatchSnapshot, type MatchCommand, type MatchSnapshot } from '../api/matches'
import { roomApi } from '../api/rooms'
import { connectMatchSocket, type MatchSocketHandlers } from '../game/matchSocket'
import { useAuthStore } from '../stores/auth'
import { matchId, snapshot, userId } from '../test/matchFixture'
import MatchView from './MatchView.vue'

vi.mock('../game/matchSocket', () => ({ connectMatchSocket: vi.fn() }))

let handlers: MatchSocketHandlers
let pinia: ReturnType<typeof createPinia>
const send = vi.fn((_command: MatchCommand) => true)
const close = vi.fn()

async function showMatch(initial: MatchSnapshot) {
  vi.spyOn(matchApi, 'state').mockResolvedValue(initial)
  vi.spyOn(roomApi, 'current').mockResolvedValue(null)
  vi.mocked(connectMatchSocket).mockImplementation((_matchId, callbacks) => {
    handlers = callbacks
    callbacks.snapshot(initial)
    callbacks.status('connected')
    return { send, close }
  })
  const router = createRouter({ history: createMemoryHistory(),
    routes: [{ path: '/matches/:id', component: MatchView }, { path: '/', component: { template: '<div />' } }] })
  await router.push(`/matches/${matchId}`)
  await router.isReady()
  const wrapper = mount(MatchView, { global: { plugins: [router, pinia] } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = { id: userId, email: 'player@example.com', nickname: '玩家' }
  auth.state = 'authenticated'
  localStorage.setItem('uno.sound.muted', 'true')
  send.mockClear()
  close.mockClear()
})
afterEach(() => { vi.restoreAllMocks(); vi.mocked(connectMatchSocket).mockReset(); localStorage.clear() })

describe('live Web table', () => {
  it('submits one chosen card, waits for acknowledgement, and ignores an older snapshot', async () => {
    const initial = parseMatchSnapshot(snapshot)
    const wrapper = await showMatch(initial)
    await wrapper.get('.hand-card').trigger('click')
    expect(wrapper.get('.hand-card').attributes('aria-pressed')).toBe('true')
    await wrapper.findAll('button').find(button => button.text() === '打出选中的牌')!.trigger('click')
    expect(send).toHaveBeenCalledTimes(1)
    expect(send.mock.calls[0]![0]).toMatchObject({ type: 'PLAY', cardId: 2, expectedVersion: 4 })
    expect(wrapper.findAll('button').find(button => button.text() === '打出选中的牌')!.attributes('disabled')).toBeDefined()
    await wrapper.findAll('button').find(button => button.text() === '打出选中的牌')!.trigger('click')
    expect(send).toHaveBeenCalledTimes(1)
    handlers.acknowledged({ ...initial, view: { ...initial.view, version: 5, ownHand: [] },
      commandId: send.mock.calls[0]![0].commandId, duplicate: false, appliedVersion: 5,
      event: 'PLAYED', challengeOutcome: 'NOT_APPLICABLE', cardsDrawnBySeat: {}, privateChallengeEvidence: [] })
    await flushPromises()
    expect(wrapper.text()).toContain('你的手牌 · 0 张')
    handlers.snapshot({ ...initial, view: { ...initial.view, version: 3 } })
    await flushPromises()
    expect(wrapper.text()).toContain('你的手牌 · 0 张')
    wrapper.unmount()
    expect(close).toHaveBeenCalled()
  })

  it('exposes the +4 challenge and UNO catch windows without hidden hands', async () => {
    const initial = parseMatchSnapshot(snapshot)
    const pending = { ...initial, view: { ...initial.view, phase: 'DRAW_FOUR_RESPONSE' as const,
      canRespondToDrawFour: true, unoVulnerableSeat: 1 } }
    const wrapper = await showMatch(pending)
    expect(wrapper.text()).toContain('你收到了 +4')
    await wrapper.findAll('button').find(button => button.text() === '质疑 +4')!.trigger('click')
    expect(send.mock.calls[0]![0]).toMatchObject({ type: 'CHALLENGE_DRAW_FOUR', expectedVersion: 4 })
    expect(wrapper.findAll('button').find(button => button.text() === '抓漏喊 UNO')!.attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('confirms deliberate departure and distinguishes it from a timeout interruption', async () => {
    const initial = parseMatchSnapshot(snapshot)
    const wrapper = await showMatch(initial)
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const leave = vi.spyOn(matchApi, 'leave').mockResolvedValue({ ...initial, status: 'INTERRUPTED',
      deadlineAt: null, interruptionReason: 'PLAYER_LEFT' })
    await wrapper.findAll('button').find(button => button.text() === '退出本局')!.trigger('click')
    expect(leave).not.toHaveBeenCalled()
    confirm.mockReturnValue(true)
    await wrapper.findAll('button').find(button => button.text() === '退出本局')!.trigger('click')
    await flushPromises()
    expect(leave).toHaveBeenCalledWith(matchId)
    wrapper.unmount()

    const interrupted = await showMatch(initial)
    handlers.snapshot({ ...initial, status: 'INTERRUPTED', deadlineAt: null,
      interruptionReason: 'PLAYER_LEFT' })
    await flushPromises()
    expect(interrupted.text()).toContain('有玩家主动退出，本局不计胜负。')
    interrupted.unmount()
  })

  it('submits the visible target user when catching a missed UNO', async () => {
    const initial = parseMatchSnapshot(snapshot)
    const vulnerable = { ...initial, view: { ...initial.view, unoVulnerableSeat: 1 } }
    const wrapper = await showMatch(vulnerable)
    await wrapper.findAll('button').find(button => button.text() === '抓漏喊 UNO')!.trigger('click')
    expect(send.mock.calls[0]![0]).toMatchObject({ type: 'CATCH_UNO',
      targetUserId: initial.view.players[1]!.userId, expectedVersion: 4 })
    wrapper.unmount()
  })

  it('clears an uncertain command on disconnect and allows an explicit retry after syncing', async () => {
    const initial = parseMatchSnapshot(snapshot)
    const wrapper = await showMatch(initial)
    await wrapper.get('.hand-card').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '打出选中的牌')!.trigger('click')
    handlers.status('disconnected')
    await flushPromises()
    expect(wrapper.text()).toContain('结果尚未确认')
    handlers.snapshot(initial)
    handlers.status('connected')
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '打出选中的牌')!.trigger('click')
    expect(send).toHaveBeenCalledTimes(2)
    expect(send.mock.calls[1]![0].commandId).not.toBe(send.mock.calls[0]![0].commandId)
    wrapper.unmount()
  })

  it('shows a server rejection and fetches the authoritative view', async () => {
    const initial = parseMatchSnapshot(snapshot)
    const wrapper = await showMatch(initial)
    await wrapper.get('.hand-card').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '打出选中的牌')!.trigger('click')
    handlers.rejected(send.mock.calls[0]![0].commandId, 'MATCH_CONFLICT')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('牌局已变化')
    expect(matchApi.state).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('你的手牌 · 1 张')
    wrapper.unmount()
  })

  it('shows an interrupted match and rejects further card actions', async () => {
    const initial = parseMatchSnapshot(snapshot)
    const wrapper = await showMatch(initial)
    handlers.snapshot({ ...initial, status: 'INTERRUPTED', deadlineAt: null })
    await flushPromises()
    expect(wrapper.text()).toContain('对局已中断')
    expect(wrapper.text()).toContain('本局不计胜负')
    expect(wrapper.get('.hand-card').attributes('disabled')).toBeDefined()
    await wrapper.get('.hand-card').trigger('click')
    expect(send).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
