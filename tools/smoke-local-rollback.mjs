import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const [stableLabel, candidateLabel] = process.argv.slice(2)
if (process.argv.length !== 4 || ![stableLabel, candidateLabel].every(label =>
  /^[a-zA-Z0-9][a-zA-Z0-9._-]*$/.test(label) && !label.includes('..'))) {
  throw new Error('Usage: node tools/smoke-local-rollback.mjs <stable-package-label> <candidate-package-label>')
}

const project = `uno-rollback-${randomUUID().slice(0, 8)}`
const gatewayPort = process.env.ROLLBACK_GATEWAY_PORT ?? '58080'
const webPort = process.env.ROLLBACK_WEB_PORT ?? '58088'
const mailpitPort = process.env.ROLLBACK_MAILPIT_PORT ?? '58025'
const smtpPort = process.env.ROLLBACK_SMTP_PORT ?? '51025'
for (const port of [gatewayPort, webPort, mailpitPort, smtpPort]) {
  if (!/^\d{2,5}$/.test(port) || Number(port) > 65535) throw new Error(`Invalid local test port: ${port}`)
}
const api = `http://127.0.0.1:${gatewayPort}`
const mailpit = `http://127.0.0.1:${mailpitPort}`
const services = ['identity-service', 'game-service', 'gateway', 'web']
const composeFiles = ['deploy/compose.yaml', 'deploy/compose.auth-local.yaml', 'deploy/compose.images-local.yaml']

function run(program, args, env = process.env) {
  const result = spawnSync(program, args, { cwd: root, env, encoding: 'utf8', maxBuffer: 4 * 1024 * 1024 })
  if (result.status !== 0) throw new Error(`${program} ${args.slice(0, 3).join(' ')} failed: ${(result.stderr || result.stdout || '').trim()}`)
  return result.stdout.trim()
}
function compose(tag, args) {
  const env = { ...process.env, UNO_LOCAL_IMAGE_TAG: tag,
    GATEWAY_PORT: gatewayPort, WEB_PORT: webPort,
    MAILPIT_WEB_PORT: mailpitPort, MAILPIT_SMTP_PORT: smtpPort,
    AUTH_ALLOWED_ORIGINS: `http://localhost:${webPort},http://127.0.0.1:${webPort}` }
  return run('docker', ['compose', '-p', project, '--env-file', 'deploy/.env',
    ...composeFiles.flatMap(file => ['-f', file]), ...args], env)
}
async function manifest(label) {
  const data = JSON.parse(await readFile(join(root, 'release', `${label}-images`, 'manifest.json'), 'utf8'))
  assert.equal(data.label, label)
  assert.equal(data.images.length, 4)
  for (const image of data.images) {
    assert.equal(run('docker', ['image', 'inspect', image.name, '--format', '{{.Id}}']), image.imageId,
      `Load the verified ${label} image archive before this smoke test`)
  }
  return data
}
async function request(path, { token, method = 'GET', body } = {}) {
  const response = await fetch(`${api}${path}`, { method, signal: AbortSignal.timeout(15000),
    headers: { Connection: 'close', 'X-UNO-Client': 'APP', ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}) })
  return { status: response.status, body: response.headers.get('content-type')?.includes('application/json')
    ? await response.json() : null }
}
async function waitForRoutes() {
  for (let attempt = 0; attempt < 40; attempt++) {
    try {
      const [game, identity] = await Promise.all([
        request('/api/system/bootstrap'), request('/api/auth/status'),
      ])
      if (game.status === 200 && identity.status === 200) return
    } catch { /* Containers can be healthy before Gateway routes accept connections. */ }
    await new Promise(resolve => setTimeout(resolve, 500))
  }
  throw new Error('Game and Identity routes did not become ready')
}
async function verificationToken(email) {
  for (let attempt = 0; attempt < 40; attempt++) {
    const listing = await (await fetch(`${mailpit}/api/v1/messages`, {
      signal: AbortSignal.timeout(10000),
    })).json()
    const message = listing.messages.find(item => item.To?.some(to => to.Address === email))
    if (message) {
      const detail = await (await fetch(`${mailpit}/api/v1/message/${encodeURIComponent(message.ID)}`, {
        signal: AbortSignal.timeout(10000),
      })).json()
      const token = detail.Text?.match(/^Token: ([A-Za-z0-9_-]{43})$/m)?.[1]
      if (token) return token
    }
    await new Promise(resolve => setTimeout(resolve, 500))
  }
  throw new Error('Local Mailpit did not deliver a verification token')
}
async function account(label) {
  const email = `uno-rollback-${randomUUID()}@example.test`
  const password = `Rollback-${randomUUID()}-1!`
  assert.equal((await request('/api/auth/register', {
    method: 'POST', body: { email, password, nickname: label },
  })).status, 202)
  assert.equal((await request('/api/auth/verify-email', {
    method: 'POST', body: { token: await verificationToken(email) },
  })).status, 204)
  const login = await request('/api/auth/login', { method: 'POST', body: { email, password } })
  assert.equal(login.status, 200)
  return login.body.accessToken
}
function assertRunningImages(expected) {
  for (const image of expected.images) {
    const container = compose(expected.tag, ['ps', '-q', image.service])
    assert.ok(container, `${image.service} must be running`)
    const actual = JSON.parse(run('docker', ['inspect', container]))[0]
    assert.equal(actual.Image, image.imageId, `${image.service} must use the selected image ID`)
    assert.equal(actual.Config.Labels['org.opencontainers.image.revision'], expected.sourceCommit)
  }
}
async function privateState(matchId, token) {
  const result = await request(`/api/matches/${matchId}/state`, { token })
  assert.equal(result.status, 200)
  return result.body.view
}
function assertPreserved(before, after) {
  assert.equal(after.version, before.version, 'in-progress match version must survive deployment')
  assert.deepEqual(after.ownHand.map(card => card.id), before.ownHand.map(card => card.id),
    'private hand must survive deployment')
  assert.equal(after.phase, before.phase)
}

const stable = await manifest(stableLabel)
const candidate = await manifest(candidateLabel)
assert.notEqual(stable.tag, candidate.tag)
let success = false
console.log(`Using isolated Compose project ${project}; preserving its database volume after the test.`)
try {
  compose(stable.tag, ['up', '--no-build', '-d', '--wait'])
  assertRunningImages(stable)
  await waitForRoutes()
  console.log(`Started ${stableLabel} with ready Game and Identity routes.`)
  const host = await account('Rollback Host')
  const guest = await account('Rollback Guest')
  const hostId = (await request('/api/users/me', { token: host })).body.id
  const guestId = (await request('/api/users/me', { token: guest })).body.id
  const tokens = new Map([[hostId, host], [guestId, guest]])
  let room = await request('/api/rooms', { token: host, method: 'POST', body: { mode: 'CLASSIC', maxPlayers: 2 } })
  assert.equal(room.status, 201)
  const roomId = room.body.id
  room = await request('/api/rooms/join', { token: guest, method: 'POST', body: { code: room.body.code } })
  assert.equal(room.status, 200)
  for (const token of [host, guest]) {
    room = await request(`/api/rooms/${roomId}/ready`, { token, method: 'POST',
      body: { ready: true, expectedVersion: room.body.version } })
    assert.equal(room.status, 200)
  }
  const started = await request(`/api/rooms/${roomId}/start`, { token: host, method: 'POST',
    body: { expectedVersion: room.body.version } })
  assert.equal(started.status, 200)
  const matchId = started.body.matchId
  const beforeHost = await privateState(matchId, host)
  const beforeGuest = await privateState(matchId, guest)

  compose(candidate.tag, ['up', '--no-build', '-d', '--wait'])
  assertRunningImages(candidate)
  await waitForRoutes()
  console.log(`Upgraded active match ${matchId} to ${candidateLabel}.`)
  assertPreserved(beforeHost, await privateState(matchId, host))
  assertPreserved(beforeGuest, await privateState(matchId, guest))
  assert.equal((await request(`/api/rooms/${roomId}/match`, { token: host })).body.matchId, matchId)

  compose(stable.tag, ['up', '--no-build', '-d', '--wait'])
  assertRunningImages(stable)
  await waitForRoutes()
  console.log(`Rolled active match ${matchId} back to ${stableLabel}.`)
  assertPreserved(beforeHost, await privateState(matchId, host))
  assertPreserved(beforeGuest, await privateState(matchId, guest))

  let settled = false
  for (let actionNumber = 0; actionNumber < 2500; actionNumber++) {
    const publicView = await privateState(matchId, host)
    if (publicView.phase === 'MATCH_OVER') { settled = true; break }
    const actorId = publicView.players[publicView.currentSeat].userId
    const actorToken = publicView.phase === 'ROUND_OVER' ? host : tokens.get(actorId)
    assert.ok(actorToken)
    const view = actorToken === host ? publicView : await privateState(matchId, actorToken)
    const command = { protocolVersion: 1, commandId: randomUUID(), expectedVersion: view.version }
    if (view.phase === 'ROUND_OVER') command.type = 'NEXT_ROUND'
    else if (view.phase === 'INITIAL_WILD_COLOR') {
      command.type = 'CHOOSE_INITIAL_COLOR'; command.chosenColor = 'RED'
    } else if (view.phase === 'DRAW_FOUR_RESPONSE') command.type = 'ACCEPT_DRAW_FOUR'
    else if (view.phase === 'AFTER_DRAW') {
      command.type = 'PLAY'; command.cardId = view.drawnCardId
    } else if (view.phase === 'TURN') {
      const top = view.topCard
      const card = view.ownHand.find(item => item.color === null || item.color === view.activeColor
        || (top.color !== null && item.kind === top.kind
          && (item.kind !== 'NUMBER' || item.number === top.number)))
      command.type = card ? 'PLAY' : 'DRAW'
      if (card) command.cardId = card.id
    } else throw new Error(`Unexpected match phase: ${view.phase}`)
    if (command.type === 'PLAY') {
      const card = view.ownHand.find(item => item.id === command.cardId)
      if (card.color === null) command.chosenColor = 'RED'
      command.callUno = view.ownHand.length === 2
    }
    const applied = await request(`/api/matches/${matchId}/commands`, {
      token: actorToken, method: 'POST', body: command,
    })
    if (applied.status === 409 && ['MATCH_CONFLICT', 'TURN_EXPIRED'].includes(applied.body?.code)) continue
    assert.equal(applied.status, 200, `Command ${command.type} failed: ${applied.body?.code}`)
  }
  assert.ok(settled, 'match must finish after rollback')
  const hostHistory = await request('/api/matches/history', { token: host })
  const guestHistory = await request('/api/matches/history', { token: guest })
  assert.equal(hostHistory.status, 200)
  assert.equal(guestHistory.status, 200)
  assert.equal(hostHistory.body.items[0].matchId, matchId)
  assert.equal(guestHistory.body.items[0].matchId, matchId)
  assert.notEqual(hostHistory.body.items[0].result, guestHistory.body.items[0].result)
  console.log(`PASS: ${stableLabel} -> ${candidateLabel} -> ${stableLabel} preserved match ${matchId}; it settled and both histories agree.`)
  success = true
} finally {
  if (success) compose(stable.tag, ['down'])
  else console.error(`Inspect isolated project ${project}; its containers and database volume were left intact.`)
}
