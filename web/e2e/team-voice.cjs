// Requires the local auth + voice Compose overlays and Chromium fake microphone support.
const { chromium } = require('playwright')
const { randomUUID } = require('node:crypto')
const assert = require('node:assert/strict')

const origin = process.env.UNO_E2E_ORIGIN ?? 'http://127.0.0.1:8088'
const mailpit = process.env.UNO_E2E_MAILPIT ?? 'http://127.0.0.1:28025'
const users = ['A1', 'B1', 'A2', 'B2'].map(label => ({
  label, email: `uno-voice-${randomUUID()}@example.test`, password: `Voice-${randomUUID()}-1!`,
}))

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
  return page.evaluate(async ({ path, body }) => {
    const csrfResponse = await fetch('/api/auth/csrf', { credentials: 'same-origin' })
    const csrf = await csrfResponse.json()
    const response = await fetch(path, {
      method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf.token },
      body: JSON.stringify(body),
    })
    return { status: response.status, body: await response.json().catch(() => null) }
  }, { path, body })
}

async function main() {
  const browser = await chromium.launch({ headless: true,
    args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] })
  const contexts = []
  const pages = []
  const errors = []
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
    }
    const [a1, b1, a2, b2] = pages
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
    await a1.waitForFunction(() => document.querySelectorAll('.voice-audio audio').length === 1,
      null, { timeout: 20000 })
    assert.equal(await b1.locator('.voice-audio audio').count(), 0, 'opponent audio is isolated')
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
      const actorPage = pages[publicState.view.currentSeat]
      const actorState = await actorPage.evaluate(async id => (await fetch(`/api/matches/${id}/state`)).json(), matchId)
      const view = actorState.view
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
      const applied = await mutate(actorPage, `/api/matches/${matchId}/commands`, action)
      if (applied.status === 409 && ['MATCH_CONFLICT', 'TURN_EXPIRED'].includes(applied.body?.code)) continue
      assert.equal(applied.status, 200, `game action ${action.type}: ${applied.body?.code}`)
    }
    assert.equal(ended, true, 'team match must finish')
    await b1.locator('.team-voice').waitFor({ state: 'detached', timeout: 10000 })
    assert.equal(await b1.evaluate(() => window.__unoCapturedTracks.every(track => track.readyState === 'ended')),
      true, 'match end releases the remaining active microphone')
    assert.deepEqual(errors, [])
    console.log('PASS: three Chromium players joined two isolated LiveKit rooms; capture starts on click, teammate audio subscribes, mute, exit and match end stop microphone tracks.')
  } finally {
    await Promise.all(contexts.map(context => context.close()))
    await browser.close()
  }
}

main().catch(error => { console.error(error); process.exitCode = 1 })
