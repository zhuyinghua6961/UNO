<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authApi } from '../api/auth'
import { safeReturnPath } from '../auth-navigation'
import { useAuthStore } from '../stores/auth'

type Mode = 'login' | 'register' | 'verify' | 'forgot' | 'reset'
const titles: Record<Mode, string> = { login: '登录你的账号', register: '创建新账号', verify: '验证邮箱', forgot: '找回密码', reset: '设置新密码' }
const actions: Record<Mode, string> = { login: '登录', register: '注册并申请验证邮件', verify: '确认验证', forgot: '申请重置邮件', reset: '重置密码' }
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const mode = ref<Mode>('login')
const email = ref('')
const password = ref('')
const nickname = ref('')
const token = ref('')
const notice = ref('')
const checking = ref(true)
const enabled = computed(() => mode.value === 'register' ? auth.capabilities?.registrationAvailable : auth.capabilities?.loginAvailable)
const disabled = computed(() => checking.value || auth.busy || !enabled.value)
const needsEmail = computed(() => ['login', 'register', 'forgot'].includes(mode.value))
const needsPassword = computed(() => ['login', 'register', 'reset'].includes(mode.value))

function clearSecrets() { password.value = ''; token.value = '' }
function switchMode(next: Mode) {
  if (auth.busy) return
  clearSecrets()
  auth.error = ''
  notice.value = ''
  mode.value = next
}

async function checkCapabilities() {
  checking.value = true
  auth.error = ''
  await auth.loadCapabilities()
  checking.value = false
}

async function submit() {
  if (disabled.value) return
  auth.error = ''
  notice.value = ''
  if (needsPassword.value && (Array.from(password.value).length < 15 || Array.from(password.value).length > 128)) {
    auth.error = '密码需要 15–128 个字符。'
    return
  }
  if (mode.value === 'register' && (!nickname.value.trim() || Array.from(nickname.value.trim()).length > 40)) {
    auth.error = '昵称需要 1–40 个字符。'
    return
  }
  if (['verify', 'reset'].includes(mode.value) && !/^[A-Za-z0-9_-]{43}$/.test(token.value.trim())) {
    auth.error = '请粘贴邮件中的完整 43 位凭证。'
    return
  }
  const currentMode = mode.value
  const input = { email: email.value.trim(), password: password.value, nickname: nickname.value.trim(), token: token.value.trim() }
  let success = false
  try {
    if (currentMode === 'login') success = await auth.login(input.email, input.password)
    else if (currentMode === 'register') success = await auth.perform(() => authApi.register(input.email, input.password, input.nickname))
    else if (currentMode === 'verify') success = await auth.perform(() => authApi.verify(input.token))
    else if (currentMode === 'forgot') success = await auth.perform(() => authApi.forgot(input.email))
    else success = await auth.reset(input.token, input.password)
  } finally { clearSecrets() }
  if (!success) return
  if (currentMode === 'login') { await router.replace(safeReturnPath(route.query.next)); return }
  if (currentMode === 'register' || currentMode === 'forgot') {
    mode.value = currentMode === 'register' ? 'verify' : 'reset'
    notice.value = '请求已受理；若账号符合条件，将收到邮件。请使用最新邮件中的凭证，不代表邮件已送达。'
  } else {
    mode.value = 'login'
    notice.value = currentMode === 'verify' ? '邮箱验证成功，现在可以登录。' : '密码已重置，旧会话已撤销，请重新登录。'
  }
}

async function resend() {
  if (disabled.value) return
  notice.value = ''
  const address = email.value.trim()
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(address)) { auth.error = '请填写要验证的邮箱。'; return }
  if (await auth.perform(() => authApi.resend(address))) notice.value = '请求已受理；若符合条件，将发送新的验证邮件，请仅使用最新凭证。'
}

onMounted(checkCapabilities)
onUnmounted(clearSecrets)
</script>

<template>
  <section class="auth-layout">
    <div>
      <p class="eyebrow">YOUR SEAT IS WAITING</p><h1>先认识你，<br />再一起玩。</h1>
      <p class="muted">账号已连接真实后端；房间与对局仍在建设中。</p>
      <p class="muted">当前为可关闭的邮箱账号开发版本。验证和重置凭证需从邮件中复制，本地开发邮件由开发收件箱接收。</p>
      <RouterLink to="/">← 返回大厅</RouterLink>
    </div>
    <div class="auth-panel">
      <nav class="auth-tabs" aria-label="账号操作">
        <button v-for="tab in (['login', 'register', 'verify', 'forgot', 'reset'] as const)" :key="tab" type="button" :disabled="auth.busy" :aria-current="mode === tab ? 'page' : undefined" @click="switchMode(tab)">{{ titles[tab] }}</button>
      </nav>
      <h2>{{ titles[mode] }}</h2>
      <p v-if="checking" role="status">正在确认账号功能…</p>
      <div v-else-if="!enabled" class="auth-notice"><p>账号功能未开放或服务不可用，当前不能提交。</p><button class="button secondary small" type="button" @click="checkCapabilities">重新检查</button></div>
      <p v-if="notice" class="auth-notice" role="status">{{ notice }}</p>
      <p v-if="auth.error" class="auth-error" role="alert">{{ auth.error }}</p>
      <form @submit.prevent="submit">
        <fieldset :disabled="disabled">
          <label v-if="needsEmail || mode === 'verify'">邮箱<input v-model="email" type="email" name="email" autocomplete="email" maxlength="254" :required="needsEmail" placeholder="you@example.com" /></label>
          <label v-if="mode === 'register'">昵称<input v-model="nickname" name="nickname" autocomplete="nickname" maxlength="80" required placeholder="牌桌上的名字" /></label>
          <label v-if="mode === 'verify' || mode === 'reset'">邮件凭证<input v-model="token" name="token" autocomplete="off" autocapitalize="off" :spellcheck="false" required maxlength="43" placeholder="粘贴最新邮件中的 43 位凭证" /></label>
          <label v-if="needsPassword">{{ mode === 'reset' ? '新密码' : '密码' }}<input v-model="password" type="password" name="password" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" maxlength="256" required aria-describedby="password-help" /><span id="password-help" class="muted">15–128 个字符，支持空格与中文；请勿复用常用密码。</span></label>
          <button class="button dark auth-submit" type="submit">{{ auth.busy ? '正在提交…' : actions[mode] }}</button>
          <button v-if="mode === 'verify'" class="button secondary auth-submit" type="button" @click="resend">重新申请验证邮件</button>
        </fieldset>
      </form>
      <p class="muted auth-footnote">登录凭证由浏览器安全 Cookie 管理，不写入本地存储。网络失败不会自动重复提交。</p>
    </div>
  </section>
</template>
