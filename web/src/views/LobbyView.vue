<script setup lang="ts">
import { useLobbyStore } from '../stores/lobby'

const lobby = useLobbyStore()
const fan = ['blue-7', 'yellow-3', 'green-reverse', 'red-2']
</script>

<template>
  <section class="hero">
    <div class="hero-copy"><p class="eyebrow">GOOD CARDS. GREAT COMPANY.</p><h1>好朋友，<br />就差你这一张。</h1><p class="hero-description">一张牌，一点默契。<br />和朋友一起，把普通的晚上玩得不一样。</p><RouterLink to="/preview" class="button dark">看看牌桌 <span aria-hidden="true">↗</span></RouterLink><small>交互演示，不会创建真实房间</small></div>
    <div class="card-art" aria-label="四色 UNO 卡牌素材展示"><div class="art-orbit"></div><span class="art-note">YOUR NEXT<br /><strong>GOOD TIME.</strong></span><img v-for="(card, index) in fan" :key="card" :src="`/game-assets/cards/${card}.png`" :alt="card" :style="{ '--index': index }" /><span class="art-star" aria-hidden="true">✳</span></div>
  </section>
  <section class="play-section"><div class="section-heading"><div><p class="eyebrow">PICK YOUR TABLE</p><h2>今天，怎么玩？</h2></div><span class="muted">模式选择已实现 · 联机功能待接入</span></div>
    <div class="mode-grid" role="group" aria-label="游戏模式">
      <button class="mode-card" :class="{ active: lobby.mode === 'CLASSIC' }" :aria-pressed="lobby.mode === 'CLASSIC'" @click="lobby.selectMode('CLASSIC')"><span class="mode-symbol classic" aria-hidden="true">①</span><span class="mode-content"><strong>经典自由局</strong><span>各自为战，先出完手牌的人获胜。</span><small>2–6 人 · 房间文字聊天</small></span><span class="mode-check">{{ lobby.mode === 'CLASSIC' ? '●' : '○' }}</span></button>
      <button class="mode-card" :class="{ active: lobby.mode === 'TEAM_2V2' }" :aria-pressed="lobby.mode === 'TEAM_2V2'" @click="lobby.selectMode('TEAM_2V2')"><span class="mode-symbol team" aria-hidden="true">②</span><span class="mode-content"><strong>默契双人组 <em>2v2</em></strong><span>拉上你的搭档，一起配合拿下这局。</span><small>4 人 · 队伍文字 · 队友语音</small></span><span class="mode-check">{{ lobby.mode === 'TEAM_2V2' ? '●' : '○' }}</span></button>
    </div>
    <div class="invite-strip"><div><strong>把牌桌留给熟悉的人。</strong><p>计划支持房间码与邀请链接，登录后就能一起玩。</p></div><RouterLink to="/login" class="button secondary">账号入口 <span aria-hidden="true">→</span></RouterLink></div>
  </section>
  <section class="notes-grid"><article><span>01 / 你的账号</span><h3>每次见面，还是你</h3><p>独立身份、个人偏好与对局记录。刷新不应该让你变成另一个玩家。</p></article><article><span>02 / 默契搭档</span><h3>只和队友，说悄悄话</h3><p>组队时独立语音频道。麦克风默认关闭，授权后再主动开麦。</p></article><article><span>03 / 轻松组局</span><h3>一桌朋友，两种玩法</h3><p>先做好经典局和 2v2。完整规则、文字聊天与实时语音将分阶段接入。</p></article></section>
</template>
