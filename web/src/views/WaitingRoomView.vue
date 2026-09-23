<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { roomApi, roomErrorMessage, type Room } from '../api/rooms'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const room = ref<Room | null>(null)
const error = ref('')
const notice = ref('')
const busy = ref(false)
let timer: ReturnType<typeof setInterval> | undefined
let active = true
const roomId = String(route.params.id ?? '')
const me = computed(() => room.value?.members.find(member => member.userId === auth.user?.id))
const isHost = computed(() => room.value?.hostUserId === auth.user?.id)
const inviteUrl = computed(() => room.value ? `${window.location.origin}/join/${room.value.code}` : '')

watch(() => auth.state, state => {
  if (state === 'guest') { room.value = null; void router.replace({ path: '/login', query: { next: route.path } }) }
  if (state === 'unavailable') { room.value = null; error.value = '暂时无法确认登录状态，请稍后重试。' }
})

async function refresh() {
  if (busy.value || !active || auth.state !== 'authenticated' || document.visibilityState === 'hidden') return
  try { const next = await roomApi.get(roomId); if (active && auth.state === 'authenticated') { if (!room.value || next.version >= room.value.version) room.value = next; error.value = '' } }
  catch (failure) { if (active) error.value = roomErrorMessage(failure) }
}

async function change(action: (current: Room) => Promise<Room | void>) {
  if (!room.value || busy.value) return
  busy.value = true
  error.value = ''; notice.value = ''
  try {
    const updated = await action(room.value)
    if (!active) return
    if (updated) room.value = updated
  } catch (failure) {
    if (active) { error.value = roomErrorMessage(failure); await refreshAfterConflict() }
  } finally { busy.value = false }
}

async function refreshAfterConflict() {
  try { room.value = await roomApi.get(roomId) } catch { /* retain the actionable error */ }
}

async function leave() {
  await change(async current => { await roomApi.leave(current.id); await router.replace('/') })
}

async function copyInvite() {
  try { await navigator.clipboard.writeText(inviteUrl.value); notice.value = '邀请链接已复制。' }
  catch { notice.value = '无法自动复制，请手动复制下方链接。' }
}

onMounted(() => { void refresh(); timer = setInterval(() => { void refresh() }, 4000) })
onUnmounted(() => { active = false; clearInterval(timer) })
</script>

<template>
  <section class="waiting-shell">
    <div class="room-heading"><div><p class="eyebrow">FRIENDS ARE GATHERING</p><h1>等待室</h1></div><RouterLink class="button secondary small" to="/">返回大厅</RouterLink></div>
    <p v-if="error" class="room-alert" role="alert">{{ error }}</p>
    <p v-if="notice" class="room-notice" role="status">{{ notice }}</p>
    <div v-if="!room" class="room-panel"><p>正在读取房间…</p><button class="button secondary small" @click="refresh">重新读取</button></div>
    <template v-else>
      <div class="waiting-grid">
        <article class="room-panel"><p class="eyebrow">INVITATION</p><h2>{{ room.mode === 'TEAM_2V2' ? '默契双人组 · 2v2' : '经典自由局' }}</h2><p>房间码 <strong class="room-code">{{ room.code }}</strong></p><p class="muted">邀请在 {{ new Date(room.expiresAt).toLocaleString() }} 前有效。</p><label class="room-link-label">邀请链接<input :value="inviteUrl" readonly aria-label="邀请链接" /></label><div class="room-actions"><button class="button secondary small" @click="copyInvite">复制邀请链接</button><button class="button secondary small" :disabled="busy" @click="refresh">刷新</button></div></article>
        <article class="room-panel"><p class="eyebrow">TABLE SETTINGS</p><h2>{{ room.members.length }} / {{ room.maxPlayers }} 人</h2><p v-if="room.mode === 'TEAM_2V2'" class="muted">A、B 两队各两人，座位交替排列。</p><div v-if="isHost && room.mode === 'CLASSIC'" class="room-settings"><label>人数上限<select :value="room.maxPlayers" :disabled="busy" @change="change(current => roomApi.settings(current, Number(($event.target as HTMLSelectElement).value)))"><option v-for="n in [2,3,4,5,6]" :key="n" :value="n" :disabled="n < room.members.length">{{ n }} 人</option></select></label></div><p class="muted">{{ room.canStart ? '所有人已准备，等待后续对局功能接入。' : '人齐并全部准备后，房间会显示可开始状态。' }}</p></article>
      </div>
      <div class="room-panel"><div class="room-heading"><h2>玩家与座位</h2><span class="muted">{{ room.state === 'WAITING' ? '等待中' : room.state }}</span></div><ol class="member-list"><li v-for="member in room.members" :key="member.userId"><span class="seat-badge">{{ member.seat + 1 }}</span><span><strong>{{ member.nickname }}</strong><small v-if="member.userId === room.hostUserId">房主</small><small v-if="member.userId === auth.user?.id">我</small></span><span v-if="room.mode === 'TEAM_2V2'" class="team-badge">{{ member.team }} 队</span><span class="ready-badge" :class="{ ready: member.ready }">{{ member.ready ? '已准备' : '未准备' }}</span></li></ol><div v-if="me && room.state === 'WAITING'" class="room-actions"><button class="button dark" :disabled="busy" @click="change(current => roomApi.ready(current, !me!.ready))">{{ me.ready ? '取消准备' : '准备' }}</button><template v-if="room.mode === 'TEAM_2V2'"><button v-for="team in (['A','B'] as const)" :key="team" class="button secondary small" :disabled="busy || me.team === team" @click="change(current => roomApi.team(current, team))">加入 {{ team }} 队</button></template><button class="button secondary small" :disabled="busy" @click="leave">离开房间</button></div></div>
      <p class="room-footnote">当前阶段可以组房、邀请、选队和准备；对局将在后续阶段接入。</p>
    </template>
  </section>
</template>
