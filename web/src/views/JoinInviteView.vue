<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { roomApi, roomErrorMessage } from '../api/rooms'

const route = useRoute()
const router = useRouter()
const busy = ref(false)
const error = ref('')
const code = String(route.params.code ?? '').toUpperCase()

async function join() {
  if (busy.value) return
  error.value = ''
  if (!/^[A-HJ-NP-Z2-9]{10}$/.test(code)) { error.value = '邀请链接中的房间码无效。'; return }
  busy.value = true
  try {
    const room = await roomApi.join(code)
    await router.replace(`/rooms/${room.id}`)
  } catch (failure) { error.value = roomErrorMessage(failure) }
  finally { busy.value = false }
}

onMounted(join)
</script>

<template>
  <section class="waiting-shell">
    <p class="eyebrow">JOIN YOUR FRIENDS</p><h1>加入好友牌桌</h1>
    <p class="muted">房间码 {{ code }}。登录后只加入你打开的这一间房。</p>
    <p v-if="busy" role="status">正在加入房间…</p>
    <p v-if="error" class="room-alert" role="alert">{{ error }}</p>
    <div class="room-actions"><button v-if="error" class="button dark" :disabled="busy" @click="join">重新尝试</button><RouterLink class="button secondary" to="/">返回大厅</RouterLink></div>
  </section>
</template>
