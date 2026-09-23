import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { AuthError, authApi } from '../api/auth'
import { useAuthStore } from '../stores/auth'
import LoginView from './LoginView.vue'
import AccountView from './AccountView.vue'

let wrapper: VueWrapper
const profile = { id: 'player', email: 'player@example.com', nickname: '玩家' }
const password = 'a long test password'
const token = 'a'.repeat(43)

async function setup(query = '') {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/login', component: LoginView }, { path: '/account', component: AccountView },
  ] })
  await router.push(`/login${query}`)
  wrapper = mount(LoginView, { global: { plugins: [router] } })
  await flushPromises()
  return router
}

async function tab(text: string) {
  await wrapper.findAll('.auth-tabs button').find(button => button.text() === text)!.trigger('click')
}

describe('account screens', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    setActivePinia(createPinia())
    vi.spyOn(authApi, 'status').mockResolvedValue({ loginAvailable: true, registrationAvailable: true })
  })
  afterEach(() => wrapper?.unmount())

  it('completes register, verify and login using actual API contracts', async () => {
    const register = vi.spyOn(authApi, 'register').mockResolvedValue(undefined)
    const verify = vi.spyOn(authApi, 'verify').mockResolvedValue(undefined)
    vi.spyOn(authApi, 'login').mockResolvedValue(profile)
    const router = await setup('?next=//evil.example')
    await tab('创建新账号')
    await wrapper.get('[name="email"]').setValue(profile.email)
    await wrapper.get('[name="nickname"]').setValue(profile.nickname)
    await wrapper.get('[name="password"]').setValue(password)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(register).toHaveBeenCalledWith(profile.email, password, profile.nickname)
    expect(wrapper.text()).toContain('不代表邮件已送达')
    await wrapper.get('[name="token"]').setValue(token)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(verify).toHaveBeenCalledWith(token)
    expect(wrapper.text()).toContain('邮箱验证成功')
    expect((wrapper.get('[name="password"]').element as HTMLInputElement).value).toBe('')
    await wrapper.get('[name="password"]').setValue(password)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/account')
    expect(useAuthStore().user).toEqual(profile)
  })

  it('completes forgot/reset and clears old account state', async () => {
    const forgot = vi.spyOn(authApi, 'forgot').mockResolvedValue(undefined)
    const reset = vi.spyOn(authApi, 'reset').mockResolvedValue(undefined)
    await setup()
    useAuthStore().user = profile
    await tab('找回密码')
    await wrapper.get('[name="email"]').setValue(profile.email)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(forgot).toHaveBeenCalledWith(profile.email)
    await wrapper.get('[name="token"]').setValue(token)
    await wrapper.get('[name="password"]').setValue(password)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(reset).toHaveBeenCalledWith(token, password)
    expect(wrapper.text()).toContain('旧会话已撤销')
    expect(useAuthStore().user).toBeNull()
  })

  it('keeps forms disabled when the backend switch is off', async () => {
    vi.spyOn(authApi, 'status').mockResolvedValue({ loginAvailable: false, registrationAvailable: false })
    const login = vi.spyOn(authApi, 'login')
    await setup()
    expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    expect(login).not.toHaveBeenCalled()
  })

  it('rejects short passwords without calling login', async () => {
    const login = vi.spyOn(authApi, 'login')
    await setup()
    await wrapper.get('[name="password"]').setValue('short')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.get('[role="alert"]').text()).toContain('15–128')
    expect(login).not.toHaveBeenCalled()
  })

  it('does not navigate after rejected credentials and clears the submitted password', async () => {
    vi.spyOn(authApi, 'login').mockRejectedValue(new AuthError(401, 'INVALID_CREDENTIALS', '登录信息无效'))
    const router = await setup()
    await wrapper.get('[name="email"]').setValue(profile.email)
    await wrapper.get('[name="password"]').setValue(password)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/login')
    expect(wrapper.get('[role="alert"]').text()).toContain('登录信息无效')
    expect((wrapper.get('[name="password"]').element as HTMLInputElement).value).toBe('')
  })

  it('clears secrets when switching account operations', async () => {
    await setup()
    await wrapper.get('[name="password"]').setValue(password)
    await tab('创建新账号')
    expect((wrapper.get('[name="password"]').element as HTMLInputElement).value).toBe('')
  })

  it('verifies the same identity on the account screen and logs out', async () => {
    const router = await setup()
    wrapper.unmount()
    const auth = useAuthStore()
    auth.user = profile
    auth.state = 'authenticated'
    vi.spyOn(authApi, 'gameSession').mockResolvedValue({ userId: profile.id, sessionId: 'session', clientType: 'WEB', expiresAt: '2026-09-07T12:00:00Z' })
    vi.spyOn(authApi, 'logout').mockResolvedValue(undefined)
    wrapper = mount(AccountView, { global: { plugins: [router] } })
    await wrapper.findAll('button').find(button => button.text() === '核对游戏服务身份')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('同一账号身份')
    await wrapper.findAll('button').find(button => button.text() === '退出登录')!.trigger('click')
    await flushPromises()
    expect(auth.user).toBeNull()
    expect(wrapper.text()).not.toContain(profile.email)
  })
})
