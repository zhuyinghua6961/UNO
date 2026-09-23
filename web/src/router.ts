import { createRouter, createWebHistory } from 'vue-router'
import LobbyView from './views/LobbyView.vue'
import LoginView from './views/LoginView.vue'
import RoomPreviewView from './views/RoomPreviewView.vue'
import AccountView from './views/AccountView.vue'
import WaitingRoomView from './views/WaitingRoomView.vue'
import JoinInviteView from './views/JoinInviteView.vue'
import MatchView from './views/MatchView.vue'
import { useAuthStore } from './stores/auth'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: LobbyView },
    { path: '/login', component: LoginView },
    { path: '/account', component: AccountView, meta: { requiresAuth: true } },
    { path: '/rooms/:id', component: WaitingRoomView, meta: { requiresAuth: true } },
    { path: '/matches/:id', component: MatchView, meta: { requiresAuth: true } },
    { path: '/join/:code', component: JoinInviteView, meta: { requiresAuth: true } },
    { path: '/preview', component: RoomPreviewView },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async to => {
  if (!to.meta.requiresAuth) return
  const auth = useAuthStore()
  await auth.restore()
  if (auth.state === 'guest') return { path: '/login', query: { next: to.path } }
})
