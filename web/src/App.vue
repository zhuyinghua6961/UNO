<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getBootstrap } from './api/system'

const backendStatus = ref('正在检查后端')
onMounted(async () => {
  try {
    await getBootstrap()
    backendStatus.value = '后端骨架已连接'
  } catch {
    backendStatus.value = '独立预览 · 后端未连接'
  }
})
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar" aria-label="主要导航">
      <RouterLink to="/" class="brand" aria-label="UNO 首页">U<span>·</span></RouterLink>
      <RouterLink to="/" class="nav-item" exact-active-class="selected"><span aria-hidden="true">▦</span>大厅</RouterLink>
      <RouterLink to="/preview" class="nav-item" active-class="selected"><span aria-hidden="true">◇</span>牌桌</RouterLink>
      <div class="sidebar-bottom">LET'S<br />PLAY.</div>
    </aside>
    <div class="main-shell">
      <header class="topbar">
        <RouterLink to="/" class="wordmark">UNO <span>好友牌桌</span></RouterLink>
        <div class="account"><span class="status" role="status">{{ backendStatus }}</span><RouterLink to="/login" class="button secondary small">登录 / 注册</RouterLink></div>
      </header>
      <main><RouterView /></main>
      <footer>开发预览 · v0.1 <span>不是可玩的正式版本 · 非官方 UNO 产品</span></footer>
    </div>
  </div>
</template>
