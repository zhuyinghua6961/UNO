<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { matchApi, matchErrorMessage, MatchError, type Card, type CardColor,
  type MatchCommand, type MatchCommandType, type MatchSnapshot } from '../api/matches'
import { roomApi, type Room } from '../api/rooms'
import { connectMatchSocket, type MatchSocketStatus } from '../game/matchSocket'
import RoomChat from '../components/RoomChat.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const matchId = computed(() => String(route.params.id ?? ''))
const snapshot = ref<MatchSnapshot | null>(null)
const room = ref<Room | null>(null)
const status = ref<MatchSocketStatus>('connecting')
const loading = ref(true)
const error = ref('')
const notice = ref('')
const evidence = ref<Card[]>([])
const selectedId = ref<number | null>(null)
const chosenColor = ref<CardColor | null>(null)
const callUno = ref(false)
const pending = ref<MatchCommand | null>(null)
const now = ref(Date.now())
const muted = ref(readMuted())
let channel: ReturnType<typeof connectMatchSocket> | null = null
let ticker: ReturnType<typeof setInterval> | undefined
let revision = 0
let disposed = false

const view = computed(() => snapshot.value?.view ?? null)
const ownSeat = computed(() => view.value?.players.find(player => player.userId === auth.user?.id)?.seat ?? -1)
const myTurn = computed(() => !!view.value && view.value.currentSeat === ownSeat.value)
const canSend = computed(() => status.value === 'connected' && !pending.value && !!view.value)
const activeTurn = computed(() => myTurn.value && ['TURN', 'AFTER_DRAW', 'INITIAL_WILD_COLOR'].includes(view.value?.phase ?? ''))
const selectedCard = computed(() => view.value?.ownHand.find(card => card.id === selectedId.value) ?? null)
const secondsLeft = computed(() => snapshot.value?.deadlineAt
  ? Math.max(0, Math.ceil((Date.parse(snapshot.value.deadlineAt) - now.value) / 1000)) : null)
const others = computed(() => view.value?.players.filter(player => player.seat !== ownSeat.value) ?? [])
const winner = computed(() => view.value?.players.find(player => player.seat === view.value?.roundWinnerSeat) ?? null)
const turnLabel = computed(() => {
  if (!view.value) return ''
  if (view.value.phase === 'ROUND_OVER') return '本轮结束'
  if (view.value.phase === 'MATCH_OVER') return '对局结束'
  return myTurn.value ? '轮到你' : `轮到 ${playerName(view.value.players[view.value.currentSeat]!.userId)}`
})
const colors: { value: CardColor; label: string }[] = [
  { value: 'RED', label: '红色' }, { value: 'YELLOW', label: '黄色' },
  { value: 'GREEN', label: '绿色' }, { value: 'BLUE', label: '蓝色' },
]

function readMuted(): boolean {
  try { return localStorage.getItem('uno.sound.muted') === 'true' } catch { return false }
}
function toggleMute() {
  muted.value = !muted.value
  try { localStorage.setItem('uno.sound.muted', String(muted.value)) } catch { /* preference stays in this tab */ }
}
function sound(event: string) {
  if (muted.value) return
  const file = event.includes('DREW') ? 'card-slide-1.ogg' : 'card-place-1.ogg'
  try { void new Audio(`/game-assets/audio/${file}`).play().catch(() => {}) } catch { /* sound is optional */ }
}
function cardName(card: Card): string {
  const color = colors.find(item => item.value === card.color)?.label ?? '万能'
  const face = card.kind === 'NUMBER' ? String(card.number) : {
    SKIP: '跳过', REVERSE: '反转', DRAW_TWO: '+2', WILD: '选色', WILD_DRAW_FOUR: '+4',
  }[card.kind]
  return `${color} ${face}`
}
function cardImage(card: Card): string {
  const color = card.color?.toLowerCase()
  const face = card.kind === 'NUMBER' ? String(card.number) : {
    SKIP: 'skip', REVERSE: 'reverse', DRAW_TWO: 'draw-two', WILD: 'wild', WILD_DRAW_FOUR: 'wild-draw-four',
  }[card.kind]
  return `/game-assets/cards/${color ? `${color}-` : ''}${face}.png`
}
function playerName(id: string): string {
  return room.value?.members.find(member => member.userId === id)?.nickname ?? `玩家 ${id.slice(0, 6)}`
}
function colorName(color: CardColor | null): string {
  return colors.find(item => item.value === color)?.label ?? '待选色'
}

function accept(next: MatchSnapshot) {
  if (snapshot.value && next.view.version < snapshot.value.view.version) return
  if (pending.value && next.view.version > pending.value.expectedVersion) {
    pending.value = null
    notice.value = '牌局已推进，已按服务器状态同步。'
  }
  if (!snapshot.value || next.view.version > snapshot.value.view.version) {
    selectedId.value = null
    chosenColor.value = null
    callUno.value = false
    evidence.value = []
  }
  snapshot.value = next
}

async function synchronize(clearError = true) {
  const current = revision
  try {
    const next = await matchApi.state(matchId.value)
    if (disposed || current !== revision) return
    accept(next)
    if (clearError) error.value = ''
  } catch (failure) { if (!disposed && current === revision) error.value = matchErrorMessage(failure) }
}

async function openMatch() {
  const current = ++revision
  channel?.close()
  channel = null
  snapshot.value = null
  room.value = null
  pending.value = null
  selectedId.value = null
  evidence.value = []
  loading.value = true
  error.value = ''
  notice.value = ''
  try {
    const next = await matchApi.state(matchId.value)
    if (disposed || current !== revision) return
    accept(next)
    channel = connectMatchSocket(matchId.value, {
      status: nextStatus => {
        if (disposed || current !== revision) return
        status.value = nextStatus
        if (nextStatus !== 'connected' && pending.value) {
          pending.value = null
          notice.value = '刚才的操作结果尚未确认；重连后请检查牌面并按需重试。'
        }
        if (nextStatus === 'unauthorized') {
          snapshot.value = null
          error.value = '对局会话已失效，请重新登录。'
        } else if (nextStatus === 'taken_over') {
          notice.value = '此牌局已在同一账号的另一窗口或设备接管。'
        }
      },
      snapshot: update => {
        if (disposed || current !== revision) return
        accept(update)
      },
      acknowledged: receipt => {
        if (disposed || current !== revision) return
        if (pending.value?.commandId === receipt.commandId) pending.value = null
        accept(receipt)
        evidence.value = receipt.privateChallengeEvidence
        notice.value = receipt.duplicate ? '已恢复上次操作的确认。' : '操作已由服务器确认。'
        error.value = ''
        if (!receipt.duplicate) sound(receipt.event)
      },
      rejected: (commandId, code) => {
        if (disposed || current !== revision || pending.value?.commandId !== commandId) return
        pending.value = null
        error.value = matchErrorMessage(new MatchError(409, code, `服务器拒绝此动作（${code}），请检查牌局状态。`))
        void synchronize(false)
      },
      error: message => { if (!disposed && current === revision) { error.value = message; void synchronize(false) } },
    })
    void roomApi.current().then(currentRoom => {
      if (!disposed && current === revision) room.value = currentRoom
    }).catch(() => {})
  } catch (failure) { if (!disposed && current === revision) error.value = matchErrorMessage(failure) }
  finally { if (!disposed && current === revision) loading.value = false }
}

function send(type: MatchCommandType, extra: Partial<MatchCommand> = {}) {
  if (!canSend.value || !view.value) return
  const command: MatchCommand = {
    protocolVersion: 1, commandId: crypto.randomUUID(), expectedVersion: view.value.version, type, ...extra,
  }
  pending.value = command
  error.value = ''
  notice.value = ''
  if (!channel?.send(command)) {
    pending.value = null
    error.value = '连接尚未就绪，请等待重连后再试。'
  }
}
function playSelected() {
  const card = selectedCard.value
  if (!card || !activeTurn.value || !view.value || view.value.phase === 'INITIAL_WILD_COLOR') return
  if (view.value.phase === 'AFTER_DRAW' && view.value.drawnCardId !== card.id) return
  if (card.color === null && !chosenColor.value) { error.value = '请先选择万能牌生效的颜色。'; return }
  send('PLAY', { cardId: card.id, chosenColor: card.color === null ? chosenColor.value! : undefined,
    callUno: view.value.ownHand.length === 2 && callUno.value })
}
function selectCard(card: Card) {
  if (!activeTurn.value || view.value?.phase === 'INITIAL_WILD_COLOR' || pending.value) return
  selectedId.value = selectedId.value === card.id ? null : card.id
  chosenColor.value = null
  error.value = ''
}
function catchUno() {
  const seat = view.value?.unoVulnerableSeat
  if (seat === null || seat === undefined || seat === ownSeat.value) return
  const targetUserId = view.value?.players[seat]?.userId
  if (targetUserId) send('CATCH_UNO', { targetUserId })
}

watch(matchId, () => { void openMatch() }, { immediate: true })
watch(() => auth.state, state => {
  if (state === 'guest') { snapshot.value = null; channel?.close(); void router.replace({ path: '/login', query: { next: route.path } }) }
  if (state === 'unavailable') { snapshot.value = null; channel?.close(); error.value = '暂时无法确认登录状态，请刷新后重试。' }
})
ticker = setInterval(() => { now.value = Date.now() }, 250)
onUnmounted(() => { disposed = true; revision++; channel?.close(); clearInterval(ticker) })
</script>

<template>
  <section class="live-match" :data-version="view?.version ?? 0">
    <div class="room-heading match-heading"><div><p class="eyebrow">CLASSIC UNO · LIVE TABLE</p><h1>经典牌桌</h1><p class="muted">第 {{ view?.roundNumber ?? '—' }} 轮 · 服务器决定出牌与胜负</p></div><div class="match-heading-actions"><button class="button secondary small" @click="toggleMute">{{ muted ? '开启音效' : '静音音效' }}</button><RouterLink class="button secondary small" to="/">返回大厅</RouterLink></div></div>
    <p v-if="error" class="room-alert" role="alert">{{ error }} <button v-if="!loading" class="inline-action" @click="synchronize()">同步状态</button></p>
    <p v-if="notice" class="room-notice" role="status">{{ notice }}</p>
    <div v-if="loading" class="room-panel"><p>正在读取牌局…</p></div>
    <div v-else-if="!view" class="room-panel"><p>暂时无法读取这场牌局。</p><button class="button dark small" @click="openMatch">重新读取</button></div>
    <template v-else>
      <div class="match-meta"><span :class="['connection', status]">{{ status === 'connected' ? '实时连接' : status === 'connecting' ? '正在连接' : status === 'unauthorized' ? '会话失效' : status === 'taken_over' ? '已由另一窗口接管' : '连接中断 · 正在重连' }}</span><span>{{ turnLabel }}</span><span v-if="secondsLeft !== null">剩余 {{ secondsLeft }} 秒</span><span>方向：{{ view.direction === 1 ? '顺时针 ↻' : '逆时针 ↺' }}</span><button v-if="status === 'taken_over'" class="button secondary small" @click="openMatch">在此接管</button></div>
      <div class="live-layout">
        <section class="live-table" aria-label="实时经典牌桌">
          <div class="live-opponents"><div v-for="player in others" :key="player.userId" :class="['opponent-chip', { current: view.currentSeat === player.seat }]"><strong>{{ playerName(player.userId) }}</strong><span>{{ player.handCount }} 张 · {{ player.score }} 分</span></div></div>
          <div class="live-center"><div class="pile"><img src="/game-assets/cards/back.png" alt="牌堆" /><small>牌堆 {{ view.drawCount }} 张</small></div><div class="pile discard"><img :src="cardImage(view.topCard)" :alt="`弃牌堆顶：${cardName(view.topCard)}`" /><small>弃牌堆 {{ view.discardCount }} 张</small></div><div class="turn-summary"><span class="eyebrow">CURRENT COLOR</span><strong :class="['color-name', view.activeColor?.toLowerCase()]">{{ colorName(view.activeColor) }}</strong><p>{{ view.phase === 'DRAW_FOUR_RESPONSE' ? '等待 +4 接受或质疑' : view.phase === 'AFTER_DRAW' ? '刚摸的牌可出或放弃' : view.phase === 'INITIAL_WILD_COLOR' ? '选择开局颜色' : view.phase === 'ROUND_OVER' ? '本轮结束' : view.phase === 'MATCH_OVER' ? '对局结束' : '按颜色、数字或符号出牌' }}</p></div></div>
          <div class="own-zone"><div class="own-label"><strong>你的手牌 · {{ view.ownHand.length }} 张</strong><span>{{ view.players[ownSeat]?.score ?? 0 }} 分</span></div><div class="live-hand"><button v-for="card in view.ownHand" :key="card.id" type="button" :class="['hand-card', { selected: selectedId === card.id, drawn: view.drawnCardId === card.id }]" :disabled="!activeTurn || !canSend || (view.phase === 'AFTER_DRAW' && view.drawnCardId !== card.id)" :aria-label="`选择${cardName(card)}`" :aria-pressed="selectedId === card.id" @click="selectCard(card)"><img :src="cardImage(card)" :alt="cardName(card)" /></button></div></div>
        </section>
        <aside class="match-controls room-panel"><h2>本回合操作</h2><p class="muted">{{ pending ? '等待服务器确认…' : status === 'connected' ? '选择卡牌后提交，结果以服务器返回为准。' : '重连后会同步当前局面。' }}</p>
          <div v-if="view.phase === 'MATCH_OVER'" class="round-result"><strong>{{ winner?.userId === auth.user?.id ? '你赢得了对局！' : `${winner ? playerName(winner.userId) : '玩家'} 赢得了对局` }}</strong><p>本轮得分 {{ view.roundPoints }} 分</p><RouterLink v-if="room" class="button dark" :to="`/rooms/${room.id}`">返回等待室 · 再来一局</RouterLink><RouterLink class="button secondary" to="/">返回大厅</RouterLink></div>
          <div v-else-if="view.phase === 'ROUND_OVER'" class="round-result"><strong>{{ winner?.userId === auth.user?.id ? '你赢得了本轮！' : `${winner ? playerName(winner.userId) : '玩家'} 赢得了本轮` }}</strong><p>本轮得分 {{ view.roundPoints }} 分</p><button class="button dark" :disabled="!canSend" @click="send('NEXT_ROUND')">开始下一轮</button></div>
          <div v-else-if="view.phase === 'INITIAL_WILD_COLOR' && myTurn" class="action-group"><strong>选择开局颜色</strong><div class="color-choices"><button v-for="color in colors" :key="color.value" :class="['color-choice', color.value.toLowerCase()]" :disabled="!canSend" @click="send('CHOOSE_INITIAL_COLOR', { chosenColor: color.value })">{{ color.label }}</button></div></div>
          <div v-else-if="view.phase === 'DRAW_FOUR_RESPONSE' && myTurn" class="action-group"><strong>你收到了 +4</strong><p class="muted">8 秒内可质疑；超时自动接受。</p><button class="button dark" :disabled="!canSend" @click="send('ACCEPT_DRAW_FOUR')">接受 · 摸 4 张</button><button class="button secondary" :disabled="!canSend" @click="send('CHALLENGE_DRAW_FOUR')">质疑 +4</button></div>
          <div v-else class="action-group"><template v-if="activeTurn && view.phase !== 'INITIAL_WILD_COLOR'"><p v-if="selectedCard">已选：{{ cardName(selectedCard) }}</p><p v-else class="muted">从手牌中选择一张。</p><div v-if="selectedCard?.color === null" class="color-choices"><button v-for="color in colors" :key="color.value" :class="['color-choice', color.value.toLowerCase(), { chosen: chosenColor === color.value }]" :disabled="!canSend" @click="chosenColor = color.value">{{ color.label }}</button></div><label v-if="view.ownHand.length === 2 && selectedCard" class="uno-check"><input v-model="callUno" type="checkbox" :disabled="!canSend" />出牌时喊 UNO</label><button class="button dark" :disabled="!canSend || !selectedCard || (selectedCard.color === null && !chosenColor)" @click="playSelected">打出选中的牌</button><button v-if="view.phase === 'TURN'" class="button secondary" :disabled="!canSend" @click="send('DRAW')">摸 1 张</button><button v-if="view.phase === 'AFTER_DRAW'" class="button secondary" :disabled="!canSend" @click="send('PASS')">不出刚摸的牌 · 结束回合</button></template><p v-else class="muted">等待当前玩家完成操作。</p></div>
          <div v-if="view.unoVulnerableSeat !== null" class="uno-window"><button v-if="view.unoVulnerableSeat === ownSeat" class="button secondary" :disabled="!canSend" @click="send('SAY_UNO')">喊 UNO！</button><button v-else class="button secondary" :disabled="!canSend" @click="catchUno">抓漏喊 UNO</button></div>
          <div v-if="evidence.length" class="challenge-evidence"><strong>质疑时的证据手牌</strong><div class="evidence-cards"><img v-for="card in evidence" :key="card.id" :src="cardImage(card)" :alt="cardName(card)" /></div></div>
          <button class="inline-action sync-button" @click="synchronize()">同步最新状态</button>
        </aside>
      </div>
      <RoomChat v-if="room" :room-id="room.id" />
    </template>
  </section>
</template>

<style scoped>
.live-match{display:grid;gap:16px}.match-heading{margin-bottom:2px}.match-heading h1{font-size:clamp(30px,4vw,48px)}.match-heading-actions{display:flex;gap:8px;flex-wrap:wrap}.match-meta{display:flex;gap:8px;flex-wrap:wrap}.match-meta>span{background:#fffdf7;border:1px solid #dedfd2;border-radius:8px;padding:7px 12px;font-size:12px}.connection.connected{color:#286d52}.connection.disconnected,.connection.unauthorized{color:#a44232}.live-layout{display:grid;grid-template-columns:minmax(0,1fr) 300px;gap:18px}.live-table{background:#355c48;border-radius:22px;min-height:570px;color:#f6f5e9;padding:24px;display:flex;flex-direction:column;justify-content:space-between;min-width:0}.live-opponents{display:flex;justify-content:center;gap:12px;flex-wrap:wrap}.opponent-chip{display:grid;gap:2px;min-width:115px;padding:8px 13px;border-radius:10px;border:1px solid #7d9b85;background:#2b503d;font-size:12px}.opponent-chip.current{outline:3px solid #f5d47d}.opponent-chip span{font-size:10px;color:#c2d5c7}.live-center{display:flex;justify-content:center;align-items:center;gap:25px;flex-wrap:wrap;padding:30px 0}.pile{display:grid;justify-items:center;gap:7px}.pile img{height:128px;width:auto;filter:drop-shadow(0 10px 8px #153b2c55)}.pile.discard img{transform:rotate(7deg)}.pile small{font-size:10px;color:#d0e0d4}.turn-summary{min-width:130px}.turn-summary .eyebrow{color:#bcd2c1}.turn-summary strong{display:block;font-size:24px}.turn-summary p{font-size:11px;color:#c5d6c8}.own-label{display:flex;justify-content:space-between;font-size:13px;margin-bottom:12px}.live-hand{display:flex;overflow-x:auto;gap:5px;padding:12px 4px 20px;align-items:end;min-height:125px}.hand-card{border:0;background:none;padding:0;flex:0 0 auto;transition:transform .15s}.hand-card img{width:68px;display:block}.hand-card.selected{transform:translateY(-12px)}.hand-card.selected img{filter:drop-shadow(0 0 6px #ffe497)}.hand-card.drawn img{outline:3px solid #ffe497;border-radius:7px}.match-controls{gap:14px;align-self:start}.match-controls h2{font-size:20px}.action-group,.round-result{display:grid;gap:10px}.action-group>.button,.round-result>.button,.uno-window>.button{width:100%}.color-choices{display:grid;grid-template-columns:1fr 1fr;gap:7px}.color-choice{border:2px solid transparent;border-radius:8px;color:#1c251d;padding:10px 6px;font-weight:700}.color-choice.red{background:#f1a295}.color-choice.yellow{background:#f4d578}.color-choice.green{background:#9ccd9d}.color-choice.blue{background:#a7c8e8}.color-choice.chosen{border-color:#1d3529;box-shadow:0 0 0 2px white inset}.uno-check{display:flex!important;align-items:center;gap:8px}.uno-check input{width:auto}.inline-action{background:none;border:0;padding:0;color:#2b6f54;text-decoration:underline;font-size:12px}.sync-button{justify-self:start}.challenge-evidence{display:grid;gap:8px;border-top:1px solid #dfdfd3;padding-top:12px;font-size:12px}.evidence-cards{display:flex;gap:4px;overflow-x:auto}.evidence-cards img{width:40px}.round-result strong{font-size:18px}.round-result p{font-size:12px}.uno-window{border-top:1px solid #dfdfd3;padding-top:12px}
@media(max-width:1000px){.live-layout{grid-template-columns:1fr}.match-controls{grid-template-columns:repeat(2,minmax(0,1fr));align-items:start}.match-controls h2,.match-controls>.muted,.match-controls .round-result,.match-controls .sync-button{grid-column:1/-1}}
@media(max-width:640px){.match-heading{align-items:start;flex-direction:column}.match-meta>span{font-size:11px;padding:5px 8px}.live-table{min-height:450px;padding:16px}.live-center{gap:12px}.pile img{height:95px}.turn-summary{min-width:100px}.turn-summary strong{font-size:20px}.hand-card img{width:55px}.match-controls{display:grid;grid-template-columns:1fr}.match-controls>*{grid-column:1/-1}}
.color-name.red{color:#ffb1a0}.color-name.yellow{color:#ffe599}.color-name.green{color:#b9edbb}.color-name.blue{color:#b9d9ff}
.connection.taken_over{color:#a44232}
</style>
