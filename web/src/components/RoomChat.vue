<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { chatApi, chatErrorMessage, type ChatItem } from '../api/chat'

const props = defineProps<{ roomId: string }>()
const messages = ref<ChatItem[]>([])
const draft = ref('')
const error = ref('')
const sending = ref(false)
const retry = ref<{ id: string; content: string } | null>(null)
let cursor = 0
let polling = false
let active = true
let timer: ReturnType<typeof setInterval> | undefined

function merge(items: ChatItem[]) {
  const known = new Set(messages.value.map(message => message.id))
  messages.value = [...messages.value, ...items.filter(message => !known.has(message.id))]
    .sort((a, b) => a.sequence - b.sequence).slice(-100)
}

async function refresh(initial = false) {
  if (polling || !active || document.visibilityState === 'hidden') return
  polling = true
  try {
    const result = await chatApi.history(props.roomId, initial ? 0 : cursor, initial)
    if (!active) return
    merge(result.items)
    cursor = Math.max(cursor, result.nextSequence)
    error.value = ''
  } catch (failure) { if (active) error.value = chatErrorMessage(failure) }
  finally { polling = false }
}

async function send() {
  if (sending.value) return
  const content = retry.value?.content ?? draft.value.trim()
  if (!content || [...content].length > 500) { error.value = '请输入 1–500 个字符。'; return }
  const command = retry.value ?? { id: crypto.randomUUID(), content }
  retry.value = command
  sending.value = true
  error.value = ''
  try {
    const saved = await chatApi.send(props.roomId, command.id, command.content)
    if (!active) return
    merge([saved])
    draft.value = ''
    retry.value = null
  } catch (failure) { if (active) error.value = chatErrorMessage(failure) }
  finally { sending.value = false }
}

function edit() { if (retry.value && draft.value.trim() !== retry.value.content) retry.value = null }

onMounted(() => { void refresh(true); timer = setInterval(() => { void refresh() }, 2000) })
onUnmounted(() => { active = false; clearInterval(timer) })
</script>

<template>
  <section class="room-panel room-chat" aria-label="房间文字消息">
    <h2>房间文字</h2>
    <p class="muted">房间成员可见 · 纯文字 · 最多 500 字</p>
    <ol class="chat-list" aria-live="polite">
      <li v-for="message in messages" :key="message.id">
        <strong>{{ message.senderNickname }}</strong>
        <time :datetime="message.createdAt">{{ new Date(message.createdAt).toLocaleTimeString() }}</time>
        <p>{{ message.content }}</p>
      </li>
    </ol>
    <p v-if="error" class="room-alert" role="alert">{{ error }}</p>
    <form @submit.prevent="send">
      <label for="room-chat-draft">消息</label>
      <textarea id="room-chat-draft" v-model="draft" rows="2" maxlength="1000" @input="edit" />
      <button class="button dark" type="submit" :disabled="sending || !draft.trim()">
        {{ sending ? '发送中…' : retry ? '重试发送' : '发送' }}
      </button>
    </form>
  </section>
</template>
