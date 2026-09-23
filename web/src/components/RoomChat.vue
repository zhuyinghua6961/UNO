<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { chatApi, chatErrorMessage, type ChatItem, type ChatScope } from '../api/chat'

const props = defineProps<{ roomId: string; teamEnabled?: boolean }>()
const scope = ref<ChatScope>('ROOM')
const messages = ref<ChatItem[]>([])
const draft = ref('')
const error = ref('')
const sending = ref(false)
const retry = ref<{ id: string; content: string } | null>(null)
let cursor = 0
let pollingScope: ChatScope | null = null
let active = true
let generation = 0
let timer: ReturnType<typeof setInterval> | undefined

function merge(items: ChatItem[]) {
  const known = new Set(messages.value.map(message => message.id))
  messages.value = [...messages.value, ...items.filter(message => !known.has(message.id))]
    .sort((a, b) => a.sequence - b.sequence).slice(-100)
}

async function refresh(initial = false) {
  const currentScope = scope.value
  const currentGeneration = generation
  if (pollingScope === currentScope || !active || document.visibilityState === 'hidden') return
  pollingScope = currentScope
  try {
    const result = await chatApi.history(props.roomId, initial ? 0 : cursor, initial, currentScope)
    if (!active || currentGeneration !== generation) return
    merge(result.items)
    cursor = Math.max(cursor, result.nextSequence)
    error.value = ''
  } catch (failure) { if (active && currentGeneration === generation) error.value = chatErrorMessage(failure) }
  finally { if (pollingScope === currentScope) pollingScope = null }
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
    const saved = await chatApi.send(props.roomId, command.id, command.content, scope.value)
    if (!active) return
    merge([saved])
    draft.value = ''
    retry.value = null
  } catch (failure) { if (active) error.value = chatErrorMessage(failure) }
  finally { sending.value = false }
}

function edit() { if (retry.value && draft.value.trim() !== retry.value.content) retry.value = null }

function selectScope(next: ChatScope) {
  if (sending.value || scope.value === next) return
  scope.value = next
  generation++
  messages.value = []
  cursor = 0
  draft.value = ''
  retry.value = null
  error.value = ''
  void refresh(true)
}

onMounted(() => { void refresh(true); timer = setInterval(() => { void refresh() }, 2000) })
onUnmounted(() => { active = false; generation++; clearInterval(timer) })
</script>

<template>
  <section class="room-panel room-chat" :aria-label="scope === 'TEAM' ? '队伍文字消息' : '房间文字消息'">
    <div v-if="teamEnabled" class="room-actions"><button type="button" class="button secondary small" :aria-pressed="scope === 'ROOM'" :disabled="sending" @click="selectScope('ROOM')">房间文字</button><button type="button" class="button secondary small" :aria-pressed="scope === 'TEAM'" :disabled="sending" @click="selectScope('TEAM')">队伍文字</button></div>
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
