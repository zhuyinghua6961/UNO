import assert from 'node:assert/strict'

const base = process.env.API_BASE_URL ?? 'http://127.0.0.1:28080'
const bootstrapResponse = await fetch(`${base}/api/system/bootstrap`)
assert.equal(bootstrapResponse.status, 200)
const bootstrap = await bootstrapResponse.json()
assert.equal(bootstrap.service, 'game-service')
assert.equal(bootstrap.stage, 'scaffold')
assert.equal(bootstrap.protocolVersion, 1)
assert.ok(Object.values(bootstrap.features).every(value => value === false))

const identityResponse = await fetch(`${base}/api/auth/status`)
assert.equal(identityResponse.status, 200)
const identity = await identityResponse.json()
assert.equal(identity.loginAvailable, false)
assert.equal(identity.registrationAvailable, false)

for (const path of ['/api/users/me', '/api/rooms/unknown', '/api/voice/token']) {
  const response = await fetch(`${base}${path}`)
  assert.ok([401, 403].includes(response.status), `${path} must reject unauthenticated access`)
}
console.log('PASS: gateway routes, truthful feature flags, and default-deny protected paths.')
