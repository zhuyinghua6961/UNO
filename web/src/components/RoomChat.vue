<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { chatApi, chatErrorMessage, type ChatItem, type ChatReportReason, type ChatScope } from '../api/chat'
import { connectChatSocket } from '../api/chatSocket'

const props = defineProps<{ roomId: string; teamEnabled?: boolean }>()
const scope = ref<ChatScope>('ROOM')
type Channel = { messages: ChatItem[]; cursor: number; unread: number; initialized: boolean; polling: boolean; error: string }
const channel = (): Channel => ({ messages: [], cursor: 0, unread: 0, initialized: false, polling: false, error: '' })
const channels = reactive<Record<ChatScope, Channel>>({ ROOM: channel(), TEAM: channel() })
const messages = computed(() => channels[scope.value].messages)
const error = computed(() => sendError.value || channels[scope.value].error)
const sendError = ref('')
const draft = ref('')
const sending = ref(false)
const retry = ref<{ id: string; content: string } | null>(null)
const reportTarget = ref<string | null>(null)
const reportReason = ref<ChatReportReason>('ABUSE')
const reportBusy = ref(false)
const reportError = ref('')
const reported = ref<string[]>([])
let active = true
let generation = 0
let timer: ReturnType<typeof setInterval> | undefined
let reconciliationTimer: ReturnType<typeof setInterval> | undefined
let socket: ReturnType<typeof connectChatSocket> | undefined

function merge(target: ChatScope, items: ChatItem[]): number {
  const state = channels[target]
  const known = new Set(state.messages.map(message => message.id))
  const added = items.filter(message => !known.has(message.id))
  const replacements = new Map(items.filter(message => message.redacted).map(message => [message.id, message]))
  state.messages = [...state.messages.map(message => replacements.get(message.id) ?? message), ...added]
    .sort((a, b) => a.sequence - b.sequence).slice(-100)
  return added.length
}

async function reconcile(target: ChatScope) {
  const state = channels[target]
  if (!state.initialized || !active || document.visibilityState === 'hidden') return
  const requestedGeneration = generation
  try {
    const result = await chatApi.history(props.roomId, 0, true, target, 100)
    if (!active || requestedGeneration !== generation) return
    const known = new Set(state.messages.map(message => message.id))
    merge(target, result.items.filter(message => known.has(message.id)))
  } catch { /* The ordinary cursor refresh remains authoritative for connectivity errors. */ }
}

function receive(item: ChatItem) {
  if (!active || item.roomId !== props.roomId) return
  const target = item.channel === 'ROOM' ? 'ROOM' : 'TEAM'
  if (target === 'TEAM' && !props.teamEnabled) return
  const state = channels[target]
  const added = merge(target, [item])
  if (added && state.initialized && target !== scope.value) state.unread++
}

function connect() {
  socket?.close()
  socket = connectChatSocket(props.roomId, {
    subscribed: () => {
      void refresh('ROOM')
      if (props.teamEnabled) void refresh('TEAM')
      void reconcile('ROOM')
      if (props.teamEnabled) void reconcile('TEAM')
    },
    message: receive,
    disconnected: () => {
      void refresh('ROOM')
      if (props.teamEnabled) void refresh('TEAM')
    },
  })
}

async function refresh(target: ChatScope, initial = false) {
  const state = channels[target]
  if (state.polling || !active || document.visibilityState === 'hidden') return
  const requestedGeneration = generation
  state.polling = true
  try {
    const result = await chatApi.history(props.roomId, initial ? 0 : state.cursor, initial, target)
    if (!active || requestedGeneration !== generation) return
    const added = merge(target, result.items)
    state.cursor = Math.max(state.cursor, result.nextSequence)
    if (state.initialized && target !== scope.value) state.unread += added
    state.initialized = true
    state.error = ''
  } catch (failure) { if (active && requestedGeneration === generation) state.error = chatErrorMessage(failure) }
  finally { state.polling = false }
}

async function send() {
  if (sending.value) return
  const content = retry.value?.content ?? draft.value.trim()
  if (!content || [...content].length > 500) { sendError.value = '请输入 1–500 个字符。'; return }
  const command = retry.value ?? { id: crypto.randomUUID(), content }
  const requestedGeneration = generation
  retry.value = command
  sending.value = true
  sendError.value = ''
  try {
    const saved = await chatApi.send(props.roomId, command.id, command.content, scope.value)
    if (!active || requestedGeneration !== generation) return
    merge(scope.value, [saved])
    draft.value = ''
    retry.value = null
  } catch (failure) { if (active && requestedGeneration === generation) sendError.value = chatErrorMessage(failure) }
  finally { if (requestedGeneration === generation) sending.value = false }
}

function edit() { if (retry.value && draft.value.trim() !== retry.value.content) retry.value = null }

function selectScope(next: ChatScope) {
  if (sending.value || scope.value === next) return
  scope.value = next
  channels[next].unread = 0
  draft.value = ''
  retry.value = null
  sendError.value = ''
  reportTarget.value = null
  reportError.value = ''
  void refresh(next, !channels[next].initialized)
}

async function submitReport() {
  const messageId = reportTarget.value
  if (!messageId || reportBusy.value) return
  const requestedGeneration = generation
  reportBusy.value = true
  reportError.value = ''
  try {
    await chatApi.report(props.roomId, messageId, reportReason.value)
    if (!active || requestedGeneration !== generation) return
    reported.value = [...reported.value, messageId]
    reportTarget.value = null
  } catch (failure) {
    if (active && requestedGeneration === generation) reportError.value = chatErrorMessage(failure)
  } finally { if (requestedGeneration === generation) reportBusy.value = false }
}

watch(() => props.roomId, () => {
  socket?.close()
  generation++
  channels.ROOM = channel()
  channels.TEAM = channel()
  scope.value = 'ROOM'
  draft.value = ''
  retry.value = null
  sendError.value = ''
  reportTarget.value = null
  reportError.value = ''
  reported.value = []
  reportBusy.value = false
  sending.value = false
  void refresh('ROOM', true)
  if (props.teamEnabled) void refresh('TEAM', true)
  connect()
})

onMounted(() => {
  void refresh('ROOM', true)
  if (props.teamEnabled) void refresh('TEAM', true)
  connect()
  timer = setInterval(() => {
    void refresh('ROOM')
    if (props.teamEnabled) void refresh('TEAM')
  }, 2000)
  reconciliationTimer = setInterval(() => {
    void reconcile('ROOM')
    if (props.teamEnabled) void reconcile('TEAM')
  }, 10000)
})
onUnmounted(() => { active = false; generation++; clearInterval(timer); clearInterval(reconciliationTimer); socket?.close() })
</script>

<template>
  <section class="room-panel room-chat" :aria-label="scope === 'TEAM' ? '队伍文字消息' : '房间文字消息'">
    <div v-if="teamEnabled" class="room-actions"><button type="button" class="button secondary small" :aria-pressed="scope === 'ROOM'" :disabled="sending" @click="selectScope('ROOM')">房间文字{{ channels.ROOM.unread ? ` · ${channels.ROOM.unread} 条未读` : '' }}</button><button type="button" class="button secondary small" :aria-pressed="scope === 'TEAM'" :disabled="sending" @click="selectScope('TEAM')">队伍文字{{ channels.TEAM.unread ? ` · ${channels.TEAM.unread} 条未读` : '' }}</button></div>
    <h2>{{ scope === 'TEAM' ? '队伍文字' : '房间文字' }}</h2>
    <p class="muted">{{ scope === 'TEAM' ? '仅当前队友可见 · 换队后不读取旧队消息' : '房间成员可见' }} · 纯文字 · 最多 500 字</p>
    <ol class="chat-list" aria-live="polite">
      <li v-for="message in messages" :key="message.id">
        <strong>{{ message.senderNickname }}</strong>
        <time :datetime="message.createdAt">{{ new Date(message.createdAt).toLocaleTimeString() }}</time>
        <p>{{ message.content }}</p>
        <span v-if="reported.includes(message.id)" class="muted">已提交举报</span>
        <button v-else type="button" class="button secondary small" @click="reportTarget = reportTarget === message.id ? null : message.id; reportError = ''">举报</button>
        <form v-if="reportTarget === message.id" @submit.prevent="submitReport">
          <label :for="`chat-report-reason-${message.id}`">举报原因</label>
          <select :id="`chat-report-reason-${message.id}`" v-model="reportReason">
            <option value="ABUSE">辱骂或骚扰</option>
            <option value="SPAM">刷屏或垃圾信息</option>
            <option value="OTHER">其他不当内容</option>
          </select>
          <button type="submit" class="button secondary small" :disabled="reportBusy">{{ reportBusy ? '提交中…' : '提交举报' }}</button>
          <p v-if="reportError" role="alert">{{ reportError }}</p>
        </form>
      </li>
    </ol>
    <p v-if="error" class="room-alert" role="alert">{{ error }}</p>
    <form @submit.prevent="send">
      <label :for="`room-chat-draft-${scope}`">消息</label>
      <textarea :id="`room-chat-draft-${scope}`" v-model="draft" rows="2" maxlength="1000" @input="edit" />
      <button class="button dark" type="submit" :disabled="sending || !draft.trim()">
        {{ sending ? '发送中…' : retry ? '重试发送' : '发送' }}
      </button>
    </form>
  </section>
</template>
