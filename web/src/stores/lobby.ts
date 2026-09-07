import { defineStore } from 'pinia'

export type GameMode = 'CLASSIC' | 'TEAM_2V2'

export const useLobbyStore = defineStore('lobby', {
  state: () => ({ mode: 'CLASSIC' as GameMode }),
  actions: {
    selectMode(mode: GameMode) { this.mode = mode },
  },
})
