<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { chatApi, chatErrorMessage, type ChatItem, type ChatScope } from '../api/chat'

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
let active = true
let generation = 0
let timer: ReturnType<typeof setInterval> | undefined

function merge(target: ChatScope, items: ChatItem[]): number {
  const state = channels[target]
  const known = new Set(state.messages.map(message => message.id))
  const added = items.filter(message => !known.has(message.id))
  state.messages = [...state.messages, ...added].sort((a, b) => a.sequence - b.sequence).slice(-100)
  return added.length
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
  void refresh(next, !channels[next].initialized)
}

watch(() => props.roomId, () => {
  generation++
  channels.ROOM = channel()
  channels.TEAM = channel()
  scope.value = 'ROOM'
  draft.value = ''
  retry.value = null
  sendError.value = ''
  sending.value = false
  void refresh('ROOM', true)
  if (props.teamEnabled) void refresh('TEAM', true)
})

onMounted(() => {
  void refresh('ROOM', true)
  if (props.teamEnabled) void refresh('TEAM', true)
  timer = setInterval(() => {
    void refresh('ROOM')
    if (props.teamEnabled) void refresh('TEAM')
  }, 2000)
})
onUnmounted(() => { active = false; generation++; clearInterval(timer) })
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
