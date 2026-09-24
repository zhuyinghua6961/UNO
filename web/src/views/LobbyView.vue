<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { roomApi, roomErrorMessage, type Room } from '../api/rooms'
import { useAuthStore } from '../stores/auth'
import { useLobbyStore } from '../stores/lobby'

const lobby = useLobbyStore()
const auth = useAuthStore()
const router = useRouter()
const fan = ['blue-7', 'yellow-3', 'green-reverse', 'red-2']
const maxPlayers = ref(4)
const code = ref('')
const current = ref<Room | null>(null)
const busy = ref(false)
const error = ref('')

watch(() => lobby.mode, mode => { if (mode === 'TEAM_2V2') maxPlayers.value = 4 })
watch(() => auth.state, state => { if (state === 'authenticated') void loadCurrent(); else current.value = null })
onMounted(() => { if (auth.state === 'authenticated') void loadCurrent() })

async function loadCurrent() {
  try { current.value = await roomApi.current() } catch (failure) { error.value = roomErrorMessage(failure) }
}

async function run(action: () => Promise<Room>) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { const room = await action(); await router.push(`/rooms/${room.id}`) }
  catch (failure) { error.value = roomErrorMessage(failure); await loadCurrent() }
  finally { busy.value = false }
}

function create() { void run(() => roomApi.create(lobby.mode, lobby.mode === 'TEAM_2V2' ? 4 : maxPlayers.value)) }
function join() {
  const normalized = code.value.trim().toUpperCase()
  if (!/^[A-HJ-NP-Z2-9]{10}$/.test(normalized)) { error.value = '请输入 10 位有效房间码。'; return }
  void run(() => roomApi.join(normalized))
}
</script>

<template>
  <section class="hero">
    <div class="hero-copy"><p class="eyebrow">GOOD CARDS. GREAT COMPANY.</p><h1>好朋友，<br />就差你这一张。</h1><p class="hero-description">一张牌，一点默契。<br />和朋友一起，把普通的晚上玩得不一样。</p><RouterLink to="/preview" class="button dark">看看牌桌 <span aria-hidden="true">↗</span></RouterLink><small>交互演示，不会创建真实房间</small></div>
    <div class="card-art" aria-label="四色 UNO 卡牌素材展示"><div class="art-orbit"></div><span class="art-note">YOUR NEXT<br /><strong>GOOD TIME.</strong></span><img v-for="(card, index) in fan" :key="card" :src="`/game-assets/cards/${card}.png`" :alt="card" :style="{ '--index': index }" /><span class="art-star" aria-hidden="true">✳</span></div>
  </section>
  <section class="play-section"><div class="section-heading"><div><p class="eyebrow">PICK YOUR TABLE</p><h2>今天，怎么玩？</h2></div><span class="muted">经典局与 2v2 对局可玩</span></div>
    <div class="mode-grid" role="group" aria-label="游戏模式">
      <button class="mode-card" :class="{ active: lobby.mode === 'CLASSIC' }" :aria-pressed="lobby.mode === 'CLASSIC'" @click="lobby.selectMode('CLASSIC')"><span class="mode-symbol classic" aria-hidden="true">①</span><span class="mode-content"><strong>经典自由局</strong><span>各自为战，累计 500 分决出胜负。</span><small>2–6 人 · 实时对局</small></span><span class="mode-check">{{ lobby.mode === 'CLASSIC' ? '●' : '○' }}</span></button>
      <button class="mode-card" :class="{ active: lobby.mode === 'TEAM_2V2' }" :aria-pressed="lobby.mode === 'TEAM_2V2'" @click="lobby.selectMode('TEAM_2V2')"><span class="mode-symbol team" aria-hidden="true">②</span><span class="mode-content"><strong>默契双人组 <em>2v2</em></strong><span>拉上你的搭档，先选队和准备。</span><small>4 人 · 实时对局</small></span><span class="mode-check">{{ lobby.mode === 'TEAM_2V2' ? '●' : '○' }}</span></button>
    </div>
    <div class="invite-strip"><div><strong>把牌桌留给熟悉的人。</strong><p>创建好友房，分享房间码或邀请链接。</p></div><RouterLink v-if="auth.state !== 'authenticated'" to="/login" class="button secondary">登录组局 <span aria-hidden="true">→</span></RouterLink></div>
    <div v-if="auth.state === 'authenticated'" class="room-lobby room-panel">
      <p v-if="error" class="room-alert" role="alert">{{ error }}</p>
      <template v-if="current"><h3>你已有等待中的房间</h3><p>房间码 {{ current.code }} · {{ current.members.length }} / {{ current.maxPlayers }} 人</p><RouterLink class="button dark" :to="`/rooms/${current.id}`">回到等待室</RouterLink></template>
      <template v-else><div><h3>创建房间</h3><label v-if="lobby.mode === 'CLASSIC'">人数上限<select v-model.number="maxPlayers"><option v-for="n in [2,3,4,5,6]" :key="n" :value="n">{{ n }} 人</option></select></label><p v-else class="muted">2v2 固定 4 人，分 A、B 两队。</p><button class="button dark" :disabled="busy" @click="create">创建好友房</button></div><form @submit.prevent="join"><h3>凭房间码加入</h3><label>10 位房间码<input v-model="code" maxlength="10" autocomplete="off" autocapitalize="characters" placeholder="例如 ABCDEFGHJK" /></label><button class="button secondary" :disabled="busy" type="submit">加入房间</button></form></template>
    </div>
    <p v-else-if="auth.state === 'unavailable'" class="room-alert" role="alert">暂时无法确认登录状态，请刷新后重试。</p>
  </section>
  <section class="notes-grid"><article><span>01 / 你的账号</span><h3>每次见面，还是你</h3><p>独立身份和经典对局。刷新后可恢复当前牌局。</p></article><article><span>02 / 默契搭档</span><h3>只和队友，说悄悄话</h3><p>2v2 支持队伍文字；可在牌桌主动开启队友语音。</p></article><article><span>03 / 轻松组局</span><h3>先来一桌经典局</h3><p>邀请朋友加入好友房，准备后由房主开始对局。</p></article></section>
</template>
