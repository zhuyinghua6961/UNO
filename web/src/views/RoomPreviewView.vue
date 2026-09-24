<script setup lang="ts">
import { ref } from 'vue'
import { useLobbyStore } from '../stores/lobby'

const lobby = useLobbyStore()
const channel = ref<'ROOM' | 'TEAM'>('ROOM')
const cards = ['red-2', 'red-7', 'blue-1', 'yellow-skip', 'green-9', 'wild', 'wild-draw-four']
</script>

<template>
  <div class="room-heading"><div><p class="eyebrow">TABLE PREVIEW</p><h2>{{ lobby.mode === 'TEAM_2V2' ? '默契双人组 · 2v2' : '经典自由局' }}</h2></div><span class="pill">示例牌桌 · 非实时对局</span></div>
  <div class="room-layout"><section class="game-table" aria-label="牌桌布局预览"><div class="opponents"><span>玩家 B <small>示例席位</small></span><span>玩家 C <small>{{ lobby.mode === 'TEAM_2V2' ? '你的队友' : '示例席位' }}</small></span><span>玩家 D <small>示例席位</small></span></div><div class="table-center"><img src="/game-assets/cards/back.png" alt="牌背" /><img src="/game-assets/cards/red-5.png" alt="红色 5 示例牌" /><div><strong>准备好你的下一张</strong><p>这是布局示例；真实对局请创建房间</p></div></div><div class="hand"><img v-for="card in cards" :key="card" :src="`/game-assets/cards/${card}.png`" :alt="card" /></div><p class="table-note">示例手牌，不代表已加入房间</p></section>
    <aside class="communication-panel"><h3>牌桌聊天</h3><div class="channel-tabs"><button :class="{ chosen: channel === 'ROOM' }" @click="channel = 'ROOM'">房间</button><button v-if="lobby.mode === 'TEAM_2V2'" :class="{ chosen: channel === 'TEAM' }" @click="channel = 'TEAM'">队伍</button></div><div class="chat-empty"><span aria-hidden="true">☏</span><strong>{{ channel === 'TEAM' ? '只和你的搭档交流' : '和同桌的人聊聊' }}</strong><p>真实房间可发送文字消息。<br />预览页不会发送任何消息。</p></div><label class="sr-only" for="chat">聊天内容</label><input id="chat" placeholder="预览页不发送消息" disabled /><div class="voice-box"><strong>队友语音</strong><p>{{ lobby.mode === 'TEAM_2V2' ? '仅队友可听见 · 不自动打开麦克风' : '仅在四人 2v2 模式中开放' }}</p><button class="button secondary" disabled>{{ lobby.mode === 'TEAM_2V2' ? '预览页不开麦' : '当前模式不支持队友语音' }}</button></div></aside></div>
</template>
