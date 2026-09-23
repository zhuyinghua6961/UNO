<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { getBootstrap } from './api/system'
import { useAuthStore } from './stores/auth'

const backendStatus = ref('正在检查后端')
const auth = useAuthStore()
let refreshTimer: ReturnType<typeof setInterval> | undefined
function restoreWhenVisible() { if (document.visibilityState === 'visible') void auth.restore() }
onMounted(async () => {
  void auth.restore()
  document.addEventListener('visibilitychange', restoreWhenVisible)
  window.addEventListener('focus', restoreWhenVisible)
  refreshTimer = setInterval(restoreWhenVisible, 60000)
  try {
    const bootstrap = await getBootstrap()
    backendStatus.value = bootstrap.features.authentication ? '账号与经典对局已接入' : '后端骨架已连接'
  } catch {
    backendStatus.value = '独立预览 · 后端未连接'
  }
})
onUnmounted(() => {
  clearInterval(refreshTimer)
  document.removeEventListener('visibilitychange', restoreWhenVisible)
  window.removeEventListener('focus', restoreWhenVisible)
})
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar" aria-label="主要导航">
      <RouterLink to="/" class="brand" aria-label="UNO 首页">U<span>·</span></RouterLink>
      <RouterLink to="/" class="nav-item" exact-active-class="selected"><span aria-hidden="true">▦</span>大厅</RouterLink>
      <RouterLink to="/preview" class="nav-item" active-class="selected"><span aria-hidden="true">◇</span>牌桌预览</RouterLink>
      <div class="sidebar-bottom">LET'S<br />PLAY.</div>
    </aside>
    <div class="main-shell">
      <header class="topbar">
        <RouterLink to="/" class="wordmark">UNO <span>好友牌桌</span></RouterLink>
        <div class="account"><span class="status" role="status">{{ backendStatus }}</span><RouterLink :to="auth.user ? '/account' : '/login'" class="button secondary small">{{ auth.user ? auth.user.nickname : '登录 / 注册' }}</RouterLink></div>
      </header>
      <main><RouterView /></main>
      <footer>开发预览 · v0.1 <span>经典对局测试中 · 非官方 UNO 产品</span></footer>
    </div>
  </div>
</template>
