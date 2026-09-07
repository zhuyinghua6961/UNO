import { createRouter, createWebHistory } from 'vue-router'
import LobbyView from './views/LobbyView.vue'
import LoginView from './views/LoginView.vue'
import RoomPreviewView from './views/RoomPreviewView.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: LobbyView },
    { path: '/login', component: LoginView },
    { path: '/preview', component: RoomPreviewView },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})
