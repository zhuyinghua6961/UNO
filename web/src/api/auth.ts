export type User = { id: string; email: string; nickname: string }
export type AuthStatus = { loginAvailable: boolean; registrationAvailable: boolean }
export type GameSession = { userId: string; sessionId: string; clientType: 'WEB'; expiresAt: string }

export class AuthError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message)
    this.name = 'AuthError'
  }
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function user(value: unknown): User {
  if (!record(value) || !['id', 'email', 'nickname'].every(key => typeof value[key] === 'string' && value[key])) {
    throw new AuthError(502, 'INVALID_RESPONSE', '账号服务返回异常，请稍后重试。')
  }
  return { id: value.id as string, email: value.email as string, nickname: value.nickname as string }
}

export function errorMessage(error: unknown): string {
  return error instanceof AuthError ? error.message : '网络连接失败，请检查连接后重试。'
}

export function createAuthApi(fetcher: typeof fetch = (...args) => fetch(...args)) {
  async function request(path: string, init: RequestInit = {}): Promise<unknown> {
    let response: Response
    try {
      response = await fetcher(path, {
        ...init, credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(12000),
      })
    } catch {
      throw new AuthError(0, 'NETWORK_ERROR', '网络连接失败或超时，请重试；上次请求可能已被处理。')
    }
    if (!response.ok) {
      const payload: unknown = await response.json().catch(() => null)
      const code = record(payload) && typeof payload.code === 'string' ? payload.code : 'REQUEST_FAILED'
      const message = response.status === 401 ? '登录信息无效、邮箱尚未验证或会话已失效，请重新登录。'
        : response.status === 403 ? '安全校验未通过，请刷新页面重试；本地开发请检查访问地址配置。'
          : response.status === 429 ? '操作过于频繁，请稍后再试。'
            : response.status >= 500 ? '账号服务暂不可用，请稍后重试。'
              : code === 'INVALID_TOKEN' ? '邮件凭证无效、已使用或已过期，请重新申请。'
                : '请求未完成，请检查填写内容后重试。'
      throw new AuthError(response.status, code, message)
    }
    if (response.status === 204) return undefined
    try { return await response.json() } catch {
      throw new AuthError(502, 'INVALID_RESPONSE', '账号服务返回异常，请稍后重试。')
    }
  }

  async function mutate(path: string, body?: unknown) {
    const csrf = await request('/api/auth/csrf')
    if (!record(csrf) || csrf.headerName !== 'X-CSRF-TOKEN' || typeof csrf.token !== 'string' || !csrf.token) {
      throw new AuthError(502, 'INVALID_CSRF', '无法取得安全凭证，请刷新页面重试。')
    }
    return request(path, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf.token },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  }

  return {
    async status(): Promise<AuthStatus> {
      const result = await request('/api/auth/status')
      if (!record(result) || result.service !== 'identity-service' || typeof result.loginAvailable !== 'boolean'
        || typeof result.registrationAvailable !== 'boolean') {
        throw new AuthError(502, 'INVALID_RESPONSE', '无法确认账号功能状态，请稍后重试。')
      }
      return { loginAvailable: result.loginAvailable, registrationAvailable: result.registrationAvailable }
    },
    async me() { return user(await request('/api/users/me')) },
    async login(email: string, password: string) {
      const result = await mutate('/api/auth/login', { email, password })
      return user(record(result) ? result.user : null)
    },
    register: (email: string, password: string, nickname: string) => mutate('/api/auth/register', { email, password, nickname }),
    verify: (token: string) => mutate('/api/auth/verify-email', { token }),
    resend: (email: string) => mutate('/api/auth/verification/request', { email }),
    forgot: (email: string) => mutate('/api/auth/password/forgot', { email }),
    reset: (token: string, password: string) => mutate('/api/auth/password/reset', { token, password }),
    logout: () => mutate('/api/auth/logout'),
    async gameSession(): Promise<GameSession> {
      const result = await request('/api/system/session')
      if (!record(result) || typeof result.userId !== 'string' || typeof result.sessionId !== 'string'
        || result.clientType !== 'WEB' || typeof result.expiresAt !== 'string' || !Number.isFinite(Date.parse(result.expiresAt))) {
        throw new AuthError(502, 'INVALID_RESPONSE', '游戏服务身份响应异常，请稍后重试。')
      }
      return { userId: result.userId, sessionId: result.sessionId, clientType: 'WEB', expiresAt: result.expiresAt }
    },
  }
}

export const authApi = createAuthApi()
