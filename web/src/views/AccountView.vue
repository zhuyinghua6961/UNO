<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { AuthError, authApi, errorMessage } from '../api/auth'
import { useAuthStore } from '../stores/auth'
import MatchHistory from '../components/MatchHistory.vue'

const auth = useAuthStore()
const router = useRouter()
const checkingGame = ref(false)
const gameMessage = ref('')
const nickname = ref('')
const profileMessage = ref('')
let checkVersion = 0
watch(() => auth.user?.id, () => { checkVersion++; checkingGame.value = false; gameMessage.value = '' })
watch(() => auth.user?.nickname, value => { nickname.value = value ?? '' }, { immediate: true })

async function saveNickname() {
  const chosen = nickname.value.trim()
  profileMessage.value = ''
  if (!chosen || [...chosen].length > 40 || /[\u0000-\u001f\u007f]/u.test(chosen)) {
    profileMessage.value = '昵称需为 1–40 个字符且不能含控制字符。'
    return
  }
  if (await auth.updateNickname(chosen)) profileMessage.value = '昵称已保存，其他设备重新读取账号后可见。'
}

async function logout() {
  gameMessage.value = ''
  if (await auth.logout()) await router.replace('/login')
}

async function checkGame() {
  if (checkingGame.value || !auth.user) return
  const version = ++checkVersion
  const userId = auth.user.id
  checkingGame.value = true
  gameMessage.value = ''
  try {
    const session = await authApi.gameSession()
    if (version !== checkVersion) return
    gameMessage.value = session.userId === userId ? '游戏服务已确认同一账号身份。' : '两端身份不一致，请退出后重新登录。'
  } catch (failure) {
    if (version !== checkVersion) return
    gameMessage.value = errorMessage(failure)
    if (failure instanceof AuthError && failure.status === 401) await auth.restore()
  } finally { if (version === checkVersion) checkingGame.value = false }
}
</script>

<template>
  <section class="account-layout">
    <p class="eyebrow">YOUR ACCOUNT</p><h1>我的账号</h1>
    <div v-if="auth.user" class="auth-panel account-panel">
      <h2>{{ auth.user.nickname }}</h2>
      <dl><dt>已验证邮箱</dt><dd>{{ auth.user.email }}</dd><dt>账号 ID</dt><dd>{{ auth.user.id }}</dd></dl>
      <label>修改昵称 <input v-model="nickname" maxlength="80" :disabled="auth.busy" /></label>
      <button type="button" class="button secondary small" :disabled="auth.busy || nickname.trim() === auth.user.nickname" @click="saveNickname">保存昵称</button>
      <p v-if="profileMessage" role="status">{{ profileMessage }}</p>
      <p class="muted">房间中的旧昵称保持本次入房时的显示，下一次入房会使用新昵称。头像和音量的跨端同步仍在建设中。</p>
      <div class="account-actions"><button type="button" class="button secondary" :disabled="checkingGame || auth.busy" @click="checkGame">{{ checkingGame ? '正在核对…' : '核对游戏服务身份' }}</button><button type="button" class="button dark" :disabled="auth.busy" @click="logout">{{ auth.busy ? '正在退出…' : '退出登录' }}</button></div>
      <p v-if="gameMessage" role="status">{{ gameMessage }}</p>
      <p v-if="auth.error" class="auth-error" role="alert">{{ auth.error }}</p>
      <MatchHistory :key="auth.user.id" />
    </div>
    <div v-else class="auth-panel">
      <p role="status">{{ auth.state === 'unavailable' ? '暂时无法确认登录状态，账号信息已隐藏。请检查网络后重试。' : auth.state === 'guest' ? '会话已失效，请重新登录。' : '正在恢复登录状态…' }}</p>
      <div class="account-actions"><button type="button" class="button secondary" @click="auth.restore">重新检查</button><RouterLink class="button dark" to="/login?next=/account">前往登录</RouterLink></div>
    </div>
  </section>
</template>
