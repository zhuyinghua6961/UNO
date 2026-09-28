// Requires the local auth + voice Compose overlays and Chromium fake microphone support.
const { chromium } = require('playwright')
const { randomUUID } = require('node:crypto')
const path = require('node:path')
const assert = require('node:assert/strict')

const origin = process.env.UNO_E2E_ORIGIN ?? 'http://127.0.0.1:8088'
const apiOrigin = process.env.UNO_E2E_API_ORIGIN ?? 'http://127.0.0.1:28080'
const mailpit = process.env.UNO_E2E_MAILPIT ?? 'http://127.0.0.1:28025'
const users = ['A1', 'B1', 'A2', 'B2'].map(label => ({
  label, email: `uno-voice-${randomUUID()}@example.test`, password: `Voice-${randomUUID()}-1!`,
}))
const csrfTokens = new WeakMap()

async function verificationToken(email) {
  for (let attempt = 0; attempt < 40; attempt++) {
    const listing = await (await fetch(`${mailpit}/api/v1/messages`)).json()
    const item = listing.messages.find(message => message.To?.some(to => to.Address === email))
    if (item) {
      const detail = await (await fetch(`${mailpit}/api/v1/message/${item.ID}`)).json()
      const token = detail.Text?.match(/^Token: ([A-Za-z0-9_-]{43})$/m)?.[1]
      if (token) return token
    }
    await new Promise(resolve => setTimeout(resolve, 500))
  }
  throw new Error('Mailpit did not deliver verification email')
}

async function mutate(page, path, body) {
  async function send(csrfToken) {
    return page.evaluate(async ({ path, body, csrfToken }) => {
      const response = await fetch(path, {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrfToken },
        body: JSON.stringify(body),
      })
      return { status: response.status, body: await response.json().catch(() => null) }
    }, { path, body, csrfToken })
  }
  async function csrf() {
    const token = await page.evaluate(async () => {
      const response = await fetch('/api/auth/csrf', { credentials: 'same-origin' })
      if (!response.ok) throw new Error(`CSRF service returned ${response.status}`)
      return (await response.json()).token
    })
    if (typeof token !== 'string' || !token) throw new Error('CSRF service returned no token')
    csrfTokens.set(page, token)
    return token
  }
  let result = await send(csrfTokens.get(page) ?? await csrf())
  if (result.status === 403 && result.body?.code === 'REQUEST_NOT_ALLOWED') {
    result = await send(await csrf())
  }
  if (path === '/api/auth/login') csrfTokens.delete(page)
  return result
}

async function nativeMutation(path, token, body) {
  const response = await fetch(`${apiOrigin}${path}`, {
    method: 'POST',
    headers: { 'X-UNO-Client': 'APP', Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return { status: response.status, body: await response.json().catch(() => null) }
}

async function remoteAudioRms(page) {
  return page.evaluate(async () => {
    const element = document.querySelector('.voice-audio audio')
    if (!(element?.srcObject instanceof MediaStream)) throw new Error('Remote audio stream is missing')
    const context = new AudioContext()
    const source = context.createMediaStreamSource(element.srcObject)
    const analyser = context.createAnalyser()
    analyser.fftSize = 2048
    const silentOutput = context.createGain()
    silentOutput.gain.value = 0
    source.connect(analyser)
    analyser.connect(silentOutput)
    silentOutput.connect(context.destination)
    const samples = new Float32Array(analyser.fftSize)
    let highest = 0
    try {
      await context.resume()
      for (let attempt = 0; attempt < 50; attempt++) {
        analyser.getFloatTimeDomainData(samples)
        const energy = samples.reduce((sum, value) => sum + value * value, 0)
        highest = Math.max(highest, Math.sqrt(energy / samples.length))
        if (highest > 0.001) break
        await new Promise(resolve => setTimeout(resolve, 100))
      }
      return highest
    } finally {
      await context.close()
    }
  })
}

async function webUiMove(page, view) {
  await page.waitForFunction(version => Number(document.querySelector('.live-match')?.dataset.version) >= version,
    view.version, { timeout: 15000 })
  if (view.phase === 'INITIAL_WILD_COLOR') {
    await page.locator('.match-controls .color-choices').getByRole('button', { name: '红色' }).click()
  } else if (view.phase === 'DRAW_FOUR_RESPONSE') {
    await page.getByRole('button', { name: '接受 · 摸 4 张' }).click()
  } else {
    const top = view.topCard
    const playable = card => card.color === null || card.color === view.activeColor
      || (top.color !== null && card.kind === top.kind
        && (card.kind !== 'NUMBER' || card.number === top.number))
    const index = view.phase === 'AFTER_DRAW'
      ? view.ownHand.findIndex(card => card.id === view.drawnCardId)
      : view.ownHand.findIndex(playable)
    if (index < 0) {
      await page.getByRole('button', { name: view.phase === 'TURN'
        ? '摸 1 张' : '不出刚摸的牌 · 结束回合' }).click()
    } else {
      const card = view.ownHand[index]
      await page.locator('.hand-card').nth(index).click()
      if (card.color === null) await page.locator('.color-choices .red').click()
      if (view.ownHand.length === 2) await page.locator('.uno-check input').check()
      await page.getByRole('button', { name: '打出选中的牌' }).click()
    }
  }
  await page.waitForFunction(version => Number(document.querySelector('.live-match')?.dataset.version) > version,
    view.version, { timeout: 15000 })
}

async function main() {
  const browser = await chromium.launch({ headless: true,
    args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] })
  const contexts = []
  const pages = []
  const errors = []
  const appTokens = []
  let replayGrant = null
  try {
    for (const user of users) {
      const context = await browser.newContext({ permissions: ['microphone'] })
      contexts.push(context)
      await context.addInitScript(() => {
        window.__unoGumCalls = 0
        window.__unoCapturedTracks = []
        const original = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices)
        navigator.mediaDevices.getUserMedia = async constraints => {
          window.__unoGumCalls++
          const stream = await original(constraints)
          window.__unoCapturedTracks.push(...stream.getTracks())
          return stream
        }
      })
      const page = await context.newPage()
      pages.push(page)
      page.on('pageerror', error => errors.push(error.message))
      await page.goto(origin)
      assert.equal((await mutate(page, '/api/auth/register', {
        email: user.email, password: user.password, nickname: user.label,
      })).status, 202)
      assert.equal((await mutate(page, '/api/auth/verify-email', {
        token: await verificationToken(user.email),
      })).status, 204)
      assert.equal((await mutate(page, '/api/auth/login', {
        email: user.email, password: user.password,
      })).status, 200)
      const appLogin = await fetch(`${apiOrigin}/api/auth/login`, {
        method: 'POST', headers: { 'X-UNO-Client': 'APP', 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: user.email, password: user.password }),
      })
      assert.equal(appLogin.status, 200)
      appTokens.push((await appLogin.json()).accessToken)
    }
    const [a1, b1, a2, b2] = pages
    a1.on('response', async response => {
      if (response.url().endsWith('/api/voice/token') && response.status() === 200) {
        replayGrant = await response.json()
      }
    })
    let room = await mutate(a1, '/api/rooms', { mode: 'TEAM_2V2', maxPlayers: 4 })
    assert.equal(room.status, 201)
    for (const page of [b1, a2, b2]) {
      room = await mutate(page, '/api/rooms/join', { code: room.body.code })
      assert.equal(room.status, 200)
    }
    for (const page of pages) {
      room = await mutate(page, `/api/rooms/${room.body.id}/ready`, {
        ready: true, expectedVersion: room.body.version,
      })
      assert.equal(room.status, 200)
    }
    const started = await mutate(a1, `/api/rooms/${room.body.id}/start`, { expectedVersion: room.body.version })
    assert.equal(started.status, 200)
    const matchId = started.body.matchId
    const outsiderContext = await browser.newContext()
    contexts.push(outsiderContext)
    const outsider = await outsiderContext.newPage()
    const outsiderUser = {
      email: `uno-outsider-${randomUUID()}@example.test`, password: `Outsider-${randomUUID()}-1!`,
    }
    await outsider.goto(origin)
    assert.equal((await mutate(outsider, '/api/auth/register', {
      ...outsiderUser, nickname: '旁观者',
    })).status, 202)
    assert.equal((await mutate(outsider, '/api/auth/verify-email', {
      token: await verificationToken(outsiderUser.email),
    })).status, 204)
    assert.equal((await mutate(outsider, '/api/auth/login', outsiderUser)).status, 200)
    for (const endpoint of [
      `/api/rooms/${room.body.id}`, `/api/matches/${matchId}/state`,
      `/api/rooms/${room.body.id}/messages?channel=ROOM`,
      `/api/rooms/${room.body.id}/messages?channel=TEAM`,
    ]) {
      const response = await outsider.evaluate(async url => {
        const result = await fetch(url, { credentials: 'same-origin' })
        return { status: result.status, body: await result.text() }
      }, endpoint)
      assert.ok([403, 404].includes(response.status), `outsider read ${endpoint}: ${response.status}`)
    }
    const outsiderGrant = await mutate(outsider, '/api/voice/token', { matchId })
    assert.ok([403, 404].includes(outsiderGrant.status), `outsider voice grant: ${outsiderGrant.status}`)
    assert.ok(!outsiderGrant.body?.token, 'outsider received a media token')
    const [a1State, b1State] = await Promise.all([a1, b1].map(page => page.evaluate(async id => {
      const response = await fetch(`/api/matches/${id}/state`, { credentials: 'same-origin' })
      if (!response.ok) throw new Error(`participant state: ${response.status}`)
      return response.json()
    }, matchId)))
    const forgedMove = await mutate(outsider, `/api/matches/${matchId}/commands`, {
      protocolVersion: 1, commandId: randomUUID(), expectedVersion: a1State.view.version,
      type: 'DRAW',
    })
    assert.ok([403, 404].includes(forgedMove.status), `outsider match command: ${forgedMove.status}`)
    const afterForgery = await a1.evaluate(async id =>
      (await fetch(`/api/matches/${id}/state`, { credentials: 'same-origin' })).json(), matchId)
    assert.equal(afterForgery.view.version, a1State.view.version, 'forged move changed the match')
    const stateFields = new Set(['deadlineAt', 'interruptionReason', 'status', 'view'])
    const viewFields = new Set([
      'activeColor', 'canRespondToDrawFour', 'currentSeat', 'direction', 'discardCount',
      'drawCount', 'drawnCardId', 'ownHand', 'phase', 'players', 'roundNumber',
      'roundPoints', 'roundWinnerSeat', 'rulesVersion', 'topCard', 'unoVulnerableSeat', 'version',
    ])
    for (const [owner, other] of [[a1State, b1State], [b1State, a1State]]) {
      assert.ok(owner.view.ownHand.length >= 7)
      assert.ok(Object.keys(other).every(key => stateFields.has(key)),
        'match response added an unreviewed field')
      assert.ok(Object.keys(other.view).every(key => viewFields.has(key)),
        'player view added an unreviewed field')
      const visibleHand = new Set(other.view.ownHand.map(card => card.id))
      for (const card of owner.view.ownHand) {
        assert.ok(!visibleHand.has(card.id), 'another player received a private card ID')
      }
    }
    console.log('PASS: outsider cannot read room, match or chat, move, or obtain voice token; opponent card IDs stay private.')
    for (const page of [a1, a2, b1]) {
      await page.goto(`${origin}/matches/${matchId}`)
      await page.getByRole('button', { name: '加入队友语音' }).waitFor({ timeout: 15000 })
      assert.equal(await page.evaluate(() => window.__unoGumCalls), 0, 'no microphone capture before click')
    }
    for (const [index, page] of [a1, a2, b1].entries()) {
      await page.getByRole('button', { name: '加入队友语音' }).click()
      await page.getByText('已加入 · 麦克风开启', { exact: false }).waitFor({ timeout: 20000 })
        .catch(async failure => {
          console.error('voice join failed', index, await page.locator('.team-voice').innerText(), errors)
          throw failure
        })
    }
    assert.ok(replayGrant, 'first teammate grant was captured')
    await a1.waitForFunction(() => document.querySelectorAll('.voice-audio audio').length === 1,
      null, { timeout: 20000 })
    await a2.waitForFunction(() => document.querySelectorAll('.voice-audio audio').length === 1,
      null, { timeout: 20000 })
    assert.ok(await remoteAudioRms(a1) > 0.001, 'A1 did not receive A2 microphone samples')
    assert.ok(await remoteAudioRms(a2) > 0.001, 'A2 did not receive A1 microphone samples')
    assert.equal(await b1.locator('.voice-audio audio').count(), 0, 'opponent audio is isolated')
    if (process.env.UNO_E2E_VOICE_REVOKE === '1') {
      await a2.getByRole('button', { name: '关闭麦克风' }).click()
      await a2.getByText('已加入 · 麦克风关闭', { exact: false }).waitFor()
      const nextGrant = a2.waitForResponse(response => response.url().endsWith('/api/voice/token')
        && response.status() === 200, { timeout: 30000 })
      assert.equal((await mutate(a1, '/api/auth/logout', {})).status, 204)
      await a1.waitForFunction(() => window.__unoCapturedTracks.every(track => track.readyState === 'ended'),
        null, { timeout: 30000 })
      const migratedGrant = await (await nextGrant).json()
      await a2.getByText('已加入 · 麦克风关闭', { exact: false }).waitFor({ timeout: 15000 })
      assert.equal(await a2.evaluate(() => window.__unoGumCalls), 1,
        'room migration did not reopen the muted microphone')
      const roomFrom = grant => JSON.parse(Buffer.from(grant.token.split('.')[1], 'base64url')).video.room
      assert.notEqual(roomFrom(migratedGrant), roomFrom(replayGrant),
        'valid teammate moved to a new room generation')
      assert.equal(await a1.evaluate(() => window.__unoCapturedTracks.every(track => track.readyState === 'ended')),
        true, 'revoked session releases microphone')
      assert.ok(Date.parse(replayGrant.expiresAt) - Date.now() > 5000,
        'replayed grant is still inside its validity window')
      const replayPage = await contexts[0].newPage()
      await replayPage.goto(origin)
      await replayPage.addScriptTag({ path: path.join(__dirname, '../node_modules/livekit-client/dist/livekit-client.umd.js') })
      const oldRoomParticipants = await replayPage.evaluate(async grant => {
        const room = new window.LivekitClient.Room()
        try {
          await room.connect(grant.url, grant.token)
          await new Promise(resolve => setTimeout(resolve, 800))
          const count = room.remoteParticipants.size
          await room.disconnect()
          return count
        } catch { return 0 }
      }, replayGrant)
      assert.equal(oldRoomParticipants, 0, 'replayed JWT cannot hear the valid teammate')
      assert.deepEqual(errors, [])
      console.log('PASS: teammates receive bidirectional fake microphone samples; revoked voice session loses media; valid teammate migrates without changing mic choice; replayed JWT is isolated.')
      return
    }
    await a2.getByRole('button', { name: '关闭麦克风' }).click()
    await a2.getByText('已加入 · 麦克风关闭', { exact: false }).waitFor()
    assert.equal(await a2.evaluate(() => window.__unoCapturedTracks.every(track => track.readyState === 'ended')),
      true, 'muting stops the real microphone track')
    await a1.waitForFunction(() => document.querySelectorAll('.voice-audio audio').length === 0,
      null, { timeout: 10000 }).catch(async failure => {
        console.error('remote audio after mute', await a1.evaluate(() => [...document.querySelectorAll('.voice-audio audio')]
          .map(element => ({ tracks: [...element.srcObject.getTracks()].map(track => ({
            state: track.readyState, muted: track.muted, enabled: track.enabled,
          })) }))), await a2.locator('.team-voice').innerText())
        throw failure
      })
    await a1.getByRole('button', { name: '退出语音' }).click()
    assert.equal(await a1.evaluate(() => window.__unoCapturedTracks.every(track => track.readyState === 'ended')),
      true, 'leaving releases the microphone')
    let ended = false
    for (let turn = 0; turn < 800; turn++) {
      const publicState = await a1.evaluate(async id => (await fetch(`/api/matches/${id}/state`)).json(), matchId)
      if (publicState.view.phase === 'MATCH_OVER') { ended = true; break }
      if (Date.parse(replayGrant.expiresAt) - Date.now() < 20000) {
        const refreshed = await mutate(a1, '/api/voice/token', { matchId })
        assert.equal(refreshed.status, 200, `refresh replay grant: ${refreshed.body?.code}`)
        replayGrant = refreshed.body
      }
      const actorPage = pages[publicState.view.currentSeat]
      const actorState = await actorPage.evaluate(async id => (await fetch(`/api/matches/${id}/state`)).json(), matchId)
      const view = actorState.view
      if (publicState.view.currentSeat !== 3) {
        await webUiMove(actorPage, view)
        continue
      }
      const action = { protocolVersion: 1, commandId: randomUUID(), expectedVersion: view.version }
      if (view.phase === 'INITIAL_WILD_COLOR') {
        action.type = 'CHOOSE_INITIAL_COLOR'
        action.chosenColor = 'RED'
      } else if (view.phase === 'DRAW_FOUR_RESPONSE') action.type = 'ACCEPT_DRAW_FOUR'
      else if (view.phase === 'AFTER_DRAW') {
        action.type = 'PLAY'
        action.cardId = view.drawnCardId
      } else if (view.phase === 'TURN') {
        const top = view.topCard
        const playable = view.ownHand.find(card => card.color === null || card.color === view.activeColor
          || (top.color !== null && card.kind === top.kind
            && (card.kind !== 'NUMBER' || card.number === top.number)))
        action.type = playable ? 'PLAY' : 'DRAW'
        if (playable) action.cardId = playable.id
      } else throw new Error(`Unexpected phase ${view.phase}`)
      if (action.type === 'PLAY') {
        const card = view.ownHand.find(item => item.id === action.cardId)
        if (card.color === null) action.chosenColor = 'RED'
        action.callUno = view.ownHand.length === 2
      }
      const applied = await nativeMutation(`/api/matches/${matchId}/commands`, appTokens[publicState.view.currentSeat], action)
      if (applied.status === 409 && ['MATCH_CONFLICT', 'TURN_EXPIRED'].includes(applied.body?.code)) continue
      assert.equal(applied.status, 200, `game action ${action.type}: ${applied.body?.code}`)
    }
    assert.equal(ended, true, 'team match must finish')
    await b1.locator('.team-voice').waitFor({ state: 'detached', timeout: 10000 })
    assert.equal(await b1.evaluate(() => window.__unoCapturedTracks.every(track => track.readyState === 'ended')),
      true, 'match end releases the remaining active microphone')
    assert.ok(Date.parse(replayGrant.expiresAt) - Date.now() > 5000, 'old grant remains within its expiry window')
    const replayPage = await contexts[0].newPage()
    await replayPage.goto(origin)
    await replayPage.addScriptTag({ path: path.join(__dirname, '../node_modules/livekit-client/dist/livekit-client.umd.js') })
    const replayOutcome = await replayPage.evaluate(async grant => {
      const room = new window.LivekitClient.Room()
      await room.connect(grant.url, grant.token)
      return await new Promise(resolve => {
        const timeout = setTimeout(() => resolve('still-connected'), 12000)
        room.on(window.LivekitClient.RoomEvent.Disconnected, () => {
          clearTimeout(timeout)
          resolve('deleted')
        })
      })
    }, replayGrant)
    assert.equal(replayOutcome, 'deleted', 'the retained cleanup task removes a room recreated by an old grant')
    assert.deepEqual(errors, [])
    console.log('PASS: teammates receive bidirectional fake microphone samples; team isolation and microphone lifecycle hold; retained cleanup deletes a room recreated by a terminal grant.')
  } finally {
    await Promise.all(contexts.map(context => context.close()))
    await browser.close()
  }
}

main().catch(error => { console.error(error); process.exitCode = 1 })
