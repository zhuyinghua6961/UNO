import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import LobbyView from './LobbyView.vue'
import RoomPreviewView from './RoomPreviewView.vue'
import { useLobbyStore } from '../stores/lobby'

describe('scaffold lobby', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('switches to 2v2 without claiming a real room exists', async () => {
    const wrapper = mount(LobbyView, { global: { stubs: { RouterLink: true } } })
    await wrapper.findAll('button')[1]!.trigger('click')
    expect(useLobbyStore().mode).toBe('TEAM_2V2')
    expect(wrapper.text()).toContain('不会创建真实房间')
  })

  it('keeps voice disabled in classic mode', () => {
    const wrapper = mount(RoomPreviewView)
    expect(wrapper.text()).toContain('当前模式不支持队友语音')
    expect(wrapper.get('.voice-box button').attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('.channel-tabs button')).toHaveLength(1)
  })

  it('shows a team channel but does not activate the microphone', async () => {
    useLobbyStore().selectMode('TEAM_2V2')
    const wrapper = mount(RoomPreviewView)
    await wrapper.findAll('.channel-tabs button')[1]!.trigger('click')
    expect(wrapper.text()).toContain('只和你的搭档交流')
    expect(wrapper.get('.voice-box button').attributes('disabled')).toBeDefined()
  })
})
