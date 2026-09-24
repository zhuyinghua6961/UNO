<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { createLocalAudioTrack, DisconnectReason, Room, RoomEvent, Track, type LocalAudioTrack,
  type RemoteTrack } from 'livekit-client'
import { voiceApi, voiceErrorMessage } from '../api/voice'

const props = defineProps<{ matchId: string }>()
const available = ref(false)
const state = ref<'idle' | 'connecting' | 'joined' | 'muted' | 'reconnecting' | 'error'>('idle')
const error = ref('')
const speaking = ref('')
const playbackBlocked = ref(false)
const audioHost = ref<HTMLDivElement | null>(null)
let currentRoom: Room | null = null
let microphone: LocalAudioTrack | null = null
const attachedAudio = new Map<RemoteTrack, HTMLMediaElement>()
let generation = 0
let disposed = false
let rejoinTimer: ReturnType<typeof setTimeout> | null = null

function detachAudio() {
  for (const [track, element] of attachedAudio) { track.detach(element); element.remove() }
  attachedAudio.clear()
  audioHost.value?.replaceChildren()
}

async function leave() {
  generation++
  if (rejoinTimer) { clearTimeout(rejoinTimer); rejoinTimer = null }
  const oldRoom = currentRoom
  const oldMic = microphone
  currentRoom = null
  microphone = null
  speaking.value = ''
  playbackBlocked.value = false
  state.value = 'idle'
  oldMic?.stop()
  detachAudio()
  if (oldRoom) await oldRoom.disconnect(true).catch(() => {})
}

async function join(options: { startMuted?: boolean; recovery?: boolean } = {}) {
  if (!available.value || !['idle', 'error', ...(options.recovery ? ['reconnecting'] : [])].includes(state.value)) return
  if (!options.startMuted && (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia)) {
    error.value = '浏览器需要 HTTPS 或本机 localhost 才能使用麦克风。'
    state.value = 'error'
    return
  }
  const attempt = ++generation
  state.value = 'connecting'
  error.value = ''
  let track: LocalAudioTrack | null = null
  let room: Room | null = null
  let adopted = false
  try {
    if (!options.startMuted) {
      // Start capture within the click handler's user gesture; release it on failure.
      track = await createLocalAudioTrack({ echoCancellation: true, noiseSuppression: true })
    }
    if (disposed || attempt !== generation) return
    const grant = await voiceApi.token(props.matchId)
    if (disposed || attempt !== generation) return
    room = new Room()
    const client = room
    currentRoom = client
    client.on(RoomEvent.TrackSubscribed, remote => {
      if (remote.kind === Track.Kind.Audio && currentRoom === client) {
        const element = remote.attach()
        element.autoplay = true
        attachedAudio.set(remote, element)
        audioHost.value?.appendChild(element)
      }
    })
    client.on(RoomEvent.TrackUnsubscribed, remote => {
      const element = attachedAudio.get(remote)
      if (element) { remote.detach(element); element.remove(); attachedAudio.delete(remote) }
    })
    client.on(RoomEvent.ActiveSpeakersChanged, participants => {
      if (currentRoom === client) speaking.value = participants.map(participant => participant.name || '队友').join('、')
    })
    client.on(RoomEvent.AudioPlaybackStatusChanged, () => {
      if (currentRoom === client) playbackBlocked.value = !client.canPlaybackAudio
    })
    client.on(RoomEvent.MediaDevicesError, failure => {
      if (currentRoom === client) error.value = voiceErrorMessage(failure)
    })
    client.on(RoomEvent.Reconnecting, () => {
      if (currentRoom === client) state.value = 'reconnecting'
    })
    client.on(RoomEvent.Reconnected, () => {
      if (currentRoom === client) state.value = microphone ? 'joined' : 'muted'
    })
    client.on(RoomEvent.Disconnected, reason => {
      if (currentRoom !== client) return
      const nextAttempt = ++generation
      const startMuted = microphone === null
      currentRoom = null
      microphone?.stop()
      microphone = null
      detachAudio()
      speaking.value = ''
      playbackBlocked.value = false
      if (reason === DisconnectReason.ROOM_DELETED && !disposed) {
        state.value = 'reconnecting'
        rejoinTimer = setTimeout(() => {
          rejoinTimer = null
          if (!disposed && generation === nextAttempt) void join({ startMuted, recovery: true })
        }, 3300)
        return
      }
      state.value = 'error'
      error.value = '语音连接已断开，请重新加入。'
    })
    await client.connect(grant.url, grant.token)
    if (disposed || attempt !== generation) return
    if (track) await client.localParticipant.publishTrack(track)
    if (disposed || attempt !== generation) return
    microphone = track
    adopted = true
    state.value = track ? 'joined' : 'muted'
    playbackBlocked.value = !client.canPlaybackAudio
  } catch (failure) {
    if (!disposed && attempt === generation) {
      state.value = 'error'
      error.value = voiceErrorMessage(failure)
    }
  } finally {
    if (!adopted) {
      track?.stop()
      if (room) {
        if (currentRoom === room) currentRoom = null
        await room.disconnect(true).catch(() => {})
      }
    }
  }
}

async function toggleMicrophone() {
  const room = currentRoom
  if (!room || !['joined', 'muted'].includes(state.value)) return
  const attempt = generation
  error.value = ''
  if (microphone) {
    const track = microphone
    microphone = null
    try { await room.localParticipant.unpublishTrack(track, true) }
    catch (failure) { if (attempt === generation) error.value = voiceErrorMessage(failure) }
    finally { track.stop() }
    if (attempt === generation) state.value = 'muted'
    return
  }
  try {
    const track = await createLocalAudioTrack({ echoCancellation: true, noiseSuppression: true })
    if (disposed || attempt !== generation || room !== currentRoom) { track.stop(); return }
    try {
      await room.localParticipant.publishTrack(track)
      if (disposed || attempt !== generation || room !== currentRoom) { track.stop(); return }
      microphone = track
      state.value = 'joined'
    } catch (failure) { track.stop(); throw failure }
  } catch (failure) { if (attempt === generation) error.value = voiceErrorMessage(failure) }
}

async function resumeAudio() {
  if (!currentRoom) return
  try { await currentRoom.startAudio(); playbackBlocked.value = !currentRoom.canPlaybackAudio }
  catch { error.value = '浏览器阻止了声音播放，请再次点击开启收听。' }
}

onMounted(async () => {
  try { available.value = await voiceApi.available() }
  catch { error.value = '暂时无法确认语音服务状态。' }
})
onUnmounted(() => { disposed = true; void leave() })
</script>

<template>
  <section class="room-panel team-voice" aria-label="队友语音">
    <div><h2>队友语音</h2><p class="muted">只有同队玩家可听见。点击加入后才会申请麦克风。</p></div>
    <div class="voice-actions">
      <button v-if="state === 'idle' || state === 'error'" class="button dark small" type="button" :disabled="!available" @click="join()">{{ available ? '加入队友语音' : '语音暂不可用' }}</button>
      <template v-else>
        <button class="button secondary small" type="button" :disabled="state === 'connecting' || state === 'reconnecting'" @click="toggleMicrophone">{{ state === 'muted' ? '打开麦克风' : '关闭麦克风' }}</button>
        <button class="button secondary small" type="button" @click="leave">退出语音</button>
      </template>
      <button v-if="playbackBlocked" class="button secondary small" type="button" @click="resumeAudio">开启收听</button>
    </div>
    <p class="muted" role="status">{{ state === 'connecting' ? '正在连接并申请麦克风…' : state === 'joined' ? '已加入 · 麦克风开启' : state === 'muted' ? '已加入 · 麦克风关闭' : state === 'reconnecting' ? '语音正在重连…' : '麦克风关闭' }}{{ speaking ? ` · 正在说话：${speaking}` : '' }}</p>
    <p v-if="error" class="room-alert" role="alert">{{ error }}</p>
    <div ref="audioHost" class="voice-audio" aria-hidden="true" />
  </section>
</template>

<style scoped>
.team-voice{display:grid;gap:10px}.team-voice h2{font-size:18px}.voice-actions{display:flex;gap:8px;flex-wrap:wrap}.voice-audio{display:none}
</style>
