import { describe, expect, it } from 'vitest'
import { safeReturnPath } from './auth-navigation'

describe('post-login destination', () => {
  it.each(['/', '/account', '/preview'])('preserves the allowed path %s', path => expect(safeReturnPath(path)).toBe(path))
  it.each(['/rooms/cae648d7-afaf-4dcd-a492-8bad87cb8c86', '/join/ABCDEFGHJK'])('preserves valid room destination %s', path => expect(safeReturnPath(path)).toBe(path))
  it.each(['https://evil.example', '//evil.example', '/\\evil.example', '%2f%2fevil.example', '/login', '/account?next=evil', '/join/INVALID', '/rooms/abc', '/join/ABCDEFGHJK?next=//evil', ['/preview'], null, undefined])('rejects unsafe or unknown destinations: %j', path => expect(safeReturnPath(path)).toBe('/account'))
})
