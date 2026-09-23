export function safeReturnPath(value: unknown): string {
  if (typeof value !== 'string') return '/account'
  if (['/', '/account', '/preview'].includes(value)) return value
  if (/^\/rooms\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)) return value
  if (/^\/join\/[A-HJ-NP-Z2-9]{10}$/i.test(value)) return value
  return '/account'
}
