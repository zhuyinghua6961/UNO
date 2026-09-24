import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import MatchHistory from './MatchHistory.vue'
import { matchApi } from '../api/matches'

afterEach(() => vi.restoreAllMocks())

describe('private match statistics', () => {
  it('shows classic and team results separately and excludes interruptions from win rate', async () => {
    vi.spyOn(matchApi, 'history').mockResolvedValue({ items: [], nextCursor: null })
    vi.spyOn(matchApi, 'stats').mockResolvedValue({
      classic: { wins: 2, losses: 1, interrupted: 3 },
      team2v2: { wins: 0, losses: 0, interrupted: 1 },
    })
    const wrapper = mount(MatchHistory)
    await flushPromises()
    expect(wrapper.text()).toContain('经典 2 胜 · 1 负 · 完赛胜率 67% · 3 场中断')
    expect(wrapper.text()).toContain('2v2 0 胜 · 0 负 · 完赛胜率 暂无 · 1 场中断')
    expect(wrapper.text()).toContain('中断场次不计入完赛胜率。')
    wrapper.unmount()
  })
})
