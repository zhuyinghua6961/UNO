import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { chatApi, type ChatItem, type ChatScope } from '../api/chat'
import RoomChat from './RoomChat.vue'

const roomId = 'cae648d7-afaf-4dcd-a492-8bad87cb8c86'
function message(scope: ChatScope, sequence: number): ChatItem {
  return {
    id: `e53572ef-9237-425b-b45a-${String(sequence).padStart(12, '0')}`,
    roomId, channel: scope === 'ROOM' ? 'ROOM' : 'TEAM_A', sequence,
    senderUserId: 'c4d75d7d-117c-45e3-a289-8e38a2fed8cc', senderNickname: 'Alice',
    clientMessageId: `4904abda-1a52-49f8-9385-${String(sequence).padStart(12, '0')}`,
    content: `${scope} message ${sequence}`, createdAt: '2026-09-24T10:00:00Z',
  }
}

afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('room chat channel awareness', () => {
  it('shows a pushed team message immediately and deduplicates the cursor replay', async () => {
    class Peer {
      onopen: (() => void) | null = null
      onmessage: ((event: { data: string }) => void) | null = null
      send = vi.fn()
      close = vi.fn()
    }
    let peer: Peer | undefined
    vi.stubGlobal('WebSocket', class { constructor() { peer = new Peer(); return peer } })
    const team: ChatItem[] = []
    vi.spyOn(chatApi, 'history').mockImplementation(async (_room, after = 0, _latest, target = 'ROOM') => {
      const items = (target === 'TEAM' ? team : []).filter(item => item.sequence > after)
      return { items, nextSequence: items.at(-1)?.sequence ?? after, hasMore: false }
    })
    const wrapper = mount(RoomChat, { props: { roomId, teamEnabled: true } })
    await flushPromises()
    peer!.onopen?.()
    const pushed = message('TEAM', 1)
    peer!.onmessage?.({ data: JSON.stringify({ protocolVersion: 1, type: 'CHAT_MESSAGE', roomId, item: pushed }) })
    await flushPromises()
    expect(wrapper.findAll('button').find(button => button.text().includes('队伍文字'))!.text()).toContain('1 条未读')
    team.push(pushed)
    await wrapper.findAll('button').find(button => button.text().includes('队伍文字'))!.trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.chat-list li')).toHaveLength(1)
    expect(wrapper.text()).toContain('TEAM message 1')
    wrapper.unmount()
    expect(peer!.close).toHaveBeenCalledOnce()
  })

  it('counts new messages on the other channel and clears the badge when viewed', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const room = [message('ROOM', 1)]
    const team: ChatItem[] = []
    vi.spyOn(chatApi, 'history').mockImplementation(async (_room, after = 0, latest = false, scope = 'ROOM') => {
      const items = (scope === 'TEAM' ? team : room).filter(item => item.sequence > after)
      return { items, nextSequence: items.at(-1)?.sequence ?? after, hasMore: false }
    })
    const wrapper = mount(RoomChat, { props: { roomId, teamEnabled: true } })
    await flushPromises()
    expect(wrapper.text()).toContain('ROOM message 1')
    expect(wrapper.text()).not.toContain('条未读')

    team.push(message('TEAM', 2))
    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    expect(wrapper.findAll('button').find(button => button.text().includes('队伍文字'))!.text()).toContain('1 条未读')
    await wrapper.findAll('button').find(button => button.text().includes('队伍文字'))!.trigger('click')
    expect(wrapper.text()).toContain('TEAM message 2')
    expect(wrapper.text()).not.toContain('条未读')

    room.push(message('ROOM', 3))
    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    expect(wrapper.findAll('button').find(button => button.text().includes('房间文字'))!.text()).toContain('1 条未读')
    await wrapper.findAll('button').find(button => button.text().includes('房间文字'))!.trigger('click')
    expect(wrapper.text()).toContain('ROOM message 3')
    expect(wrapper.text()).not.toContain('条未读')
    wrapper.unmount()
  })

  it('drops cached and late messages when the component changes rooms', async () => {
    let releaseOld!: (value: { items: ChatItem[]; nextSequence: number; hasMore: boolean }) => void
    const oldResponse = new Promise<{ items: ChatItem[]; nextSequence: number; hasMore: boolean }>(resolve => { releaseOld = resolve })
    const newRoomId = '8a72d495-6c78-468d-ab46-342a3104cc12'
    vi.spyOn(chatApi, 'history').mockImplementation(async (requestedRoom, _after, _latest, target) => {
      if (requestedRoom === roomId && target === 'ROOM') return oldResponse
      const items = requestedRoom === newRoomId && target === 'ROOM'
        ? [{ ...message('ROOM', 2), roomId: newRoomId, content: 'New room message' }] : []
      return { items, nextSequence: items.at(-1)?.sequence ?? 0, hasMore: false }
    })
    const wrapper = mount(RoomChat, { props: { roomId, teamEnabled: true } })
    await wrapper.setProps({ roomId: newRoomId })
    await flushPromises()
    expect(wrapper.text()).toContain('New room message')
    releaseOld({ items: [{ ...message('ROOM', 1), content: 'Old room secret' }], nextSequence: 1, hasMore: false })
    await flushPromises()
    expect(wrapper.text()).not.toContain('Old room secret')
    wrapper.unmount()
  })

  it('keeps polling from the previous cursor after a send so intervening messages arrive', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const room = [message('ROOM', 1)]
    const sent = { ...message('ROOM', 3), content: 'My message' }
    const history = vi.spyOn(chatApi, 'history').mockImplementation(async (_room, after = 0) => {
      const items = room.filter(item => item.sequence > after)
      return { items, nextSequence: items.at(-1)?.sequence ?? after, hasMore: false }
    })
    vi.spyOn(chatApi, 'send').mockResolvedValue(sent)
    const wrapper = mount(RoomChat, { props: { roomId } })
    await flushPromises()
    await wrapper.get('textarea').setValue('My message')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('My message')
    room.push(message('ROOM', 2), sent)
    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    expect(history.mock.calls.map(call => call[1])).toContain(1)
    expect(wrapper.text()).toContain('ROOM message 2')
    expect(wrapper.findAll('.chat-list li')).toHaveLength(3)
    wrapper.unmount()
  })
})
