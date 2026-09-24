export type VoiceGrant = { url: string; token: string; expiresAt: string }

export class VoiceError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message)
    this.name = 'VoiceError'
  }
}

const object = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)

export function voiceErrorMessage(error: unknown): string {
  if (error instanceof VoiceError) return error.message
  if (error instanceof DOMException) {
    if (error.name === 'NotAllowedError' || error.name === 'PermissionDeniedError')
      return '麦克风权限被拒绝，请在浏览器地址栏允许后重试。'
    if (error.name === 'NotFoundError') return '没有找到可用的麦克风。'
    if (error.name === 'NotReadableError') return '麦克风正在被其他程序占用。'
  }
  return '队友语音连接失败，请检查麦克风、网络和媒体服务后重试。'
}

export function createVoiceApi(fetcher: typeof fetch = (...args) => fetch(...args)) {
  async function request(path: string, init: RequestInit = {}): Promise<unknown> {
    let response: Response
    try {
      response = await fetcher(path, {
        ...init, credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(12000),
      })
    } catch { throw new VoiceError(0, 'NETWORK_ERROR', '语音服务连接失败或超时，请稍后重试。') }
    if (!response.ok) {
      const payload: unknown = await response.json().catch(() => null)
      const code = object(payload) && typeof payload.code === 'string' ? payload.code : 'REQUEST_FAILED'
      const message = response.status === 401 ? '登录已失效，请重新登录。'
        : response.status === 404 ? '当前对局没有可加入的队友语音。'
          : response.status === 429 ? '语音加入过快，请稍后重试。'
            : response.status >= 500 ? '队友语音暂不可用，请稍后重试。'
              : '无法加入队友语音，请刷新后重试。'
      throw new VoiceError(response.status, code, message)
    }
    try { return await response.json() } catch { throw new VoiceError(502, 'INVALID_RESPONSE', '语音服务返回异常。') }
  }

  return {
    async available(): Promise<boolean> {
      const result = await request('/api/system/bootstrap')
      return object(result) && object(result.features) && result.features.teamVoice === true
    },
    async token(matchId: string): Promise<VoiceGrant> {
      const csrf = await request('/api/auth/csrf')
      if (!object(csrf) || csrf.headerName !== 'X-CSRF-TOKEN' || typeof csrf.token !== 'string' || !csrf.token)
        throw new VoiceError(502, 'INVALID_CSRF', '无法取得安全凭证，请刷新页面重试。')
      const result = await request('/api/voice/token', {
        method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf.token },
        body: JSON.stringify({ matchId }),
      })
      if (!object(result) || typeof result.url !== 'string' || !/^wss?:\/\//.test(result.url)
        || typeof result.token !== 'string' || result.token.split('.').length !== 3
        || typeof result.expiresAt !== 'string' || !Number.isFinite(Date.parse(result.expiresAt)))
        throw new VoiceError(502, 'INVALID_RESPONSE', '语音服务返回异常。')
      return { url: result.url, token: result.token, expiresAt: result.expiresAt }
    },
  }
}

export const voiceApi = createVoiceApi()
