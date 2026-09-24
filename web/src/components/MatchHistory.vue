<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { matchApi, matchErrorMessage, type HistoryItem } from '../api/matches'

const items = ref<HistoryItem[]>([])
const cursor = ref<string | null>(null)
const loading = ref(false)
const loaded = ref(false)
const error = ref('')
let revision = 0
let disposed = false

async function load(reset = false) {
  if (loading.value) return
  const current = ++revision
  if (reset) { items.value = []; cursor.value = null; loaded.value = false }
  loading.value = true
  error.value = ''
  try {
    const page = await matchApi.history(cursor.value ?? undefined)
    if (disposed || current !== revision) return
    items.value = [...items.value, ...page.items]
    cursor.value = page.nextCursor
    loaded.value = true
  } catch (failure) {
    if (!disposed && current === revision) error.value = matchErrorMessage(failure)
  } finally {
    if (!disposed && current === revision) loading.value = false
  }
}

function players(item: HistoryItem) {
  return item.players.map(player => `${player.nickname ?? `玩家 ${player.userId.slice(0, 6)}`} ${player.score} 分`).join(' · ')
}

onMounted(() => { void load() })
onUnmounted(() => { disposed = true; revision++ })
</script>

<template>
  <section class="history-panel" aria-label="对局战绩">
    <div class="history-heading"><h2>对局战绩</h2><button class="button secondary small" :disabled="loading" @click="load(true)">刷新</button></div>
    <p v-if="error" class="room-alert" role="alert">{{ error }} <button class="inline-action" @click="load()">重试</button></p>
    <p v-if="loaded && items.length === 0" class="muted">还没有已完成的对局。</p>
    <ol v-if="items.length" class="history-list">
      <li v-for="item in items" :key="item.matchId">
        <strong>{{ item.mode === 'TEAM_2V2' ? '2v2 · ' : '经典 · ' }}{{ item.result === 'WIN' ? '胜利' : item.result === 'INTERRUPTED' ? '中断 · 不计胜负' : '未获胜' }}</strong>
        <span>{{ new Date(item.endedAt).toLocaleString('zh-CN') }} · {{ item.rounds }} 轮</span>
        <small>{{ players(item) }}</small>
      </li>
    </ol>
    <button v-if="cursor" class="button secondary small" :disabled="loading" @click="load()">{{ loading ? '读取中…' : '加载更多' }}</button>
    <p v-if="loading && !items.length" role="status">正在读取战绩…</p>
  </section>
</template>

<style scoped>
.history-panel{display:grid;gap:12px;margin-top:16px;padding:20px;background:#fffdf7;border:1px solid #dedfd2;border-radius:14px}.history-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.history-heading h2{margin:0;font-size:20px}.history-list{list-style:none;margin:0;padding:0;display:grid;gap:8px}.history-list li{display:grid;gap:3px;padding:12px;border:1px solid #dedfd2;border-radius:9px}.history-list li strong{color:#286d52}.history-list li span,.history-list li small{color:#526158}.inline-action{border:0;background:none;color:#2b6f54;text-decoration:underline}
</style>
