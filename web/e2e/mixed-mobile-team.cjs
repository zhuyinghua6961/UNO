// Run against the isolated local auth/voice Compose stack and a booted mobile simulator.
const { chromium } = require('playwright')
const { randomUUID } = require('node:crypto')
const { spawn } = require('node:child_process')
const path = require('node:path')
const assert = require('node:assert/strict')

const origin = process.env.UNO_E2E_ORIGIN ?? 'http://127.0.0.1:8088'
const apiOrigin = process.env.UNO_E2E_API_ORIGIN ?? 'http://127.0.0.1:28080'
const mailpit = process.env.UNO_E2E_MAILPIT ?? 'http://127.0.0.1:28025'
const deviceId = process.env.UNO_E2E_DEVICE_ID ?? process.env.UNO_E2E_SIMULATOR_ID
const platform = process.env.UNO_E2E_PLATFORM ?? 'mobile'
const voiceEnabled = process.env.UNO_E2E_MOBILE_VOICE === '1'
const users = ['Web A1', 'Web B1', 'Web A2'].map(label => ({
  label, email: `uno-mixed-${randomUUID()}@example.test`, password: `Mixed-${randomUUID()}-1!`,
}))
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))

async function verificationToken(email) {
  for (let attempt = 0; attempt < 40; attempt++) {
    const listing = await (await fetch(`${mailpit}/api/v1/messages`)).json()
    const item = listing.messages.find(message => message.To?.some(to => to.Address === email))
    if (item) {
      const detail = await (await fetch(`${mailpit}/api/v1/message/${item.ID}`)).json()
      const token = detail.Text?.match(/^Token: ([A-Za-z0-9_-]{43})$/m)?.[1]
      if (token) return token
    }
    await delay(500)
  }
  throw new Error('local verification email was not delivered')
}

async function mutate(page, url, body) {
  async function csrf() {
    const response = await read(page, '/api/auth/csrf')
    assert.equal(response.status, 200, `CSRF request failed: ${response.body?.code}`)
    const token = response.body?.token
    assert.equal(typeof token, 'string')
    return token
  }
  async function send(token) {
    return page.evaluate(async ({ url, body, token }) => {
      const response = await fetch(url, { method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': token },
        body: JSON.stringify(body) })
      return { status: response.status, body: await response.json().catch(() => null) }
    }, { url, body, token })
  }
  let result = await send(await csrf())
  if (result.status === 403 && result.body?.code === 'REQUEST_NOT_ALLOWED') result = await send(await csrf())
  return result
}

async function read(page, url) {
  return page.evaluate(async url => {
    const response = await fetch(url, { credentials: 'same-origin' })
    return { status: response.status, body: await response.json().catch(() => null) }
  }, url)
}

function startMobile(roomCode) {
  assert.ok(deviceId, 'pass UNO_E2E_DEVICE_ID')
  const args = ['test', 'integration_test/local_mobile_team_play_test.dart', '-d', deviceId,
    '--dart-define=UNO_LOCAL_MOBILE_TEAM_E2E=true', `--dart-define=API_BASE_URL=${apiOrigin}`,
    `--dart-define=UNO_TEAM_ROOM_CODE=${roomCode}`]
  if (voiceEnabled) args.push('--dart-define=UNO_LOCAL_MOBILE_TEAM_VOICE_E2E=true')
  const child = spawn('flutter', args, { cwd: path.resolve(__dirname, '../../flutter'), env: process.env })
  const output = []
  for (const stream of [child.stdout, child.stderr]) {
    stream.on('data', chunk => {
      output.push(...chunk.toString().split(/\r?\n/).filter(Boolean))
      if (output.length > 100) output.splice(0, output.length - 100)
    })
  }
  const done = new Promise((resolve, reject) => {
    child.on('error', reject)
    child.on('close', code => code === 0 ? resolve() : reject(new Error(`${platform} integration test exited ${code}:\n${output.slice(-80).join('\n')}`)))
  })
  const mobile = { child, done, output, failure: null }
  done.catch(error => { mobile.failure = error })
  return mobile
}

function automaticAction(view) {
  const action = { protocolVersion: 1, commandId: randomUUID(), expectedVersion: view.version }
  if (view.phase === 'INITIAL_WILD_COLOR') return { ...action, type: 'CHOOSE_INITIAL_COLOR', chosenColor: 'RED' }
  if (view.phase === 'DRAW_FOUR_RESPONSE') return { ...action, type: 'ACCEPT_DRAW_FOUR' }
  if (view.phase === 'ROUND_OVER') return { ...action, type: 'NEXT_ROUND' }
  if (view.phase !== 'TURN' && view.phase !== 'AFTER_DRAW') throw new Error(`unexpected phase ${view.phase}`)
  const top = view.topCard
  const playable = view.ownHand.find(card => (view.phase !== 'AFTER_DRAW' || card.id === view.drawnCardId)
    && (card.color === null || card.color === view.activeColor ||
      (top.color !== null && card.kind === top.kind && (card.kind !== 'NUMBER' || card.number === top.number))))
  if (!playable) return { ...action, type: view.phase === 'TURN' ? 'DRAW' : 'PASS' }
  return { ...action, type: 'PLAY', cardId: playable.id,
    ...(playable.color === null ? { chosenColor: 'RED' } : {}), callUno: view.ownHand.length === 2 }
}

async function webUiMove(page, view) {
  await page.getByText('实时连接', { exact: true }).waitFor({ timeout: 15000 })
  const before = view.version
  if (view.phase === 'TURN') await page.getByRole('button', { name: '摸 1 张' }).click()
  else if (view.phase === 'AFTER_DRAW') await page.getByRole('button', { name: '不出刚摸的牌 · 结束回合' }).click()
  else if (view.phase === 'INITIAL_WILD_COLOR') await page.locator('.match-controls .color-choices').getByRole('button', { name: '红色' }).click()
  else if (view.phase === 'DRAW_FOUR_RESPONSE') await page.getByRole('button', { name: '接受 · 摸 4 张' }).click()
  else throw new Error(`unexpected Web UI phase ${view.phase}`)
  await page.waitForFunction(version => Number(document.querySelector('.live-match')?.dataset.version) > version,
    before, { timeout: 15000 })
}

async function main() {
  const browser = await chromium.launch({ headless: true })
  const contexts = []
  const pages = []
  const pageErrors = []
  let mobile
  try {
    for (const user of users) {
      const context = await browser.newContext()
      contexts.push(context)
      const page = await context.newPage()
      pages.push(page)
      page.on('pageerror', error => pageErrors.push(error.message))
      await page.goto(origin)
      let result = await mutate(page, '/api/auth/register', {
        email: user.email, password: user.password, nickname: user.label })
      assert.equal(result.status, 202, `register: ${result.body?.code}`)
      result = await mutate(page, '/api/auth/verify-email', { token: await verificationToken(user.email) })
      assert.equal(result.status, 204, `verify: ${result.body?.code}`)
      result = await mutate(page, '/api/auth/login', { email: user.email, password: user.password })
      assert.equal(result.status, 200, `login: ${result.body?.code}`)
    }
    const [a1, b1, a2] = pages
    await a1.goto(origin)
    await a1.getByRole('button', { name: /默契双人组/ }).click()
    await a1.getByRole('button', { name: '创建好友房' }).click()
    await a1.getByRole('heading', { name: '等待室' }).waitFor()
    let room = (await read(a1, '/api/rooms/current')).body
    assert.equal(room.mode, 'TEAM_2V2')
    for (const page of [b1, a2]) {
      await page.goto(origin)
      await page.getByRole('textbox', { name: '10 位房间码' }).fill(room.code)
      await page.getByRole('button', { name: '加入房间' }).click()
      await page.getByRole('heading', { name: '等待室' }).waitFor()
    }
    mobile = startMobile(room.code)
    const roomUrl = `/api/rooms/${room.id}`
    // A first Android or iOS build/install can exceed three minutes.
    for (let attempt = 0; attempt < 480; attempt++) {
      if (mobile.failure) throw mobile.failure
      room = (await read(a1, roomUrl)).body
      if (room.members.length === 4) break
      await delay(1000)
    }
    assert.equal(room.members.length, 4, `${platform} did not join: ${mobile.output.slice(-12).join(' | ')}`)
    assert.deepEqual(room.members.map(member => member.team), ['A', 'B', 'A', 'B'])
    for (const page of pages) {
      await page.getByRole('button', { name: '刷新', exact: true }).click()
      await page.getByRole('heading', { name: '4 / 4 人' }).waitFor()
      await page.getByRole('button', { name: '准备', exact: true }).click()
      await page.getByRole('button', { name: '取消准备' }).waitFor()
    }
    for (let attempt = 0; attempt < 90; attempt++) {
      if (mobile.failure) throw mobile.failure
      room = (await read(a1, roomUrl)).body
      if (room.canStart) break
      await delay(500)
    }
    assert.equal(room.canStart, true, `all four players must be ready: ${room.members.map(member => `${member.seat}:${member.ready}`).join(',')}; ${platform} output: ${mobile.output.slice(-20).join('\n')}`)
    await a1.getByRole('button', { name: '刷新', exact: true }).click()
    await a1.getByRole('button', { name: '开始对局' }).click()
    await a1.getByRole('heading', { name: '双人组牌桌' }).waitFor()
    const matchId = (await read(a1, `/api/rooms/${room.id}/match`)).body.matchId
    for (const page of [b1, a2]) {
      await page.goto(`${origin}/matches/${matchId}`)
      await page.getByRole('heading', { name: '双人组牌桌' }).waitFor()
    }
    if (voiceEnabled) {
      for (let attempt = 0; attempt < 180; attempt++) {
        if (mobile.failure) throw mobile.failure
        if (mobile.output.some(line => line.includes('UNO_MOBILE_VOICE_LEFT'))) break
        await delay(500)
      }
      assert.ok(mobile.output.some(line => line.includes('UNO_MOBILE_VOICE_LISTENING'))
        && mobile.output.some(line => line.includes('UNO_MOBILE_VOICE_LEFT')),
      `${platform} did not join and leave listen-only voice: ${mobile.output.slice(-30).join('\n')}`)
    }
    let webActed = false
    let mobileActed = false
    let ended = false
    for (let turn = 0; turn < 800; turn++) {
      if (mobile.failure) throw mobile.failure
      const publicState = (await read(a1, `/api/matches/${matchId}/state`)).body
      if (publicState.view.phase === 'MATCH_OVER') { ended = true; break }
      assert.equal(publicState.status, 'PLAYING')
      const seat = publicState.view.currentSeat
      if (seat === 3) {
        const before = publicState.view.version
        for (let attempt = 0; attempt < 60; attempt++) {
          await delay(500)
          if (mobile.failure) throw mobile.failure
          const next = (await read(a1, `/api/matches/${matchId}/state`)).body
          if (next.view.version > before) { mobileActed = true; break }
        }
        const next = (await read(a1, `/api/matches/${matchId}/state`)).body
        assert.ok(next.view.version > before, `${platform} did not act: ${mobile.output.slice(-80).join('\n')}`)
        continue
      }
      const actor = pages[seat]
      if (!webActed) {
        await webUiMove(actor, publicState.view)
        webActed = true
        continue
      }
      const state = (await read(actor, `/api/matches/${matchId}/state`)).body
      const action = automaticAction(state.view)
      const result = await mutate(actor, `/api/matches/${matchId}/commands`, action)
      if (result.status === 409) continue
      assert.equal(result.status, 200, `action ${action.type}: ${JSON.stringify(result.body)} from ${actor.url()}`)
    }
    assert.equal(ended, true, 'team match did not finish')
    assert.equal(webActed, true, 'a Web UI turn was not submitted')
    assert.equal(mobileActed, true, `a ${platform} UI turn was not submitted`)
    const finalState = (await read(a1, `/api/matches/${matchId}/state`)).body
    const winningTeam = finalState.view.roundWinnerSeat % 2 === 0 ? 'A' : 'B'
    for (const [seat, page] of pages.entries()) {
      const history = (await read(page, '/api/matches/history')).body
      assert.equal(history.items[0].matchId, matchId)
      assert.equal(history.items[0].mode, 'TEAM_2V2')
      assert.equal(history.items[0].result, (seat % 2 === 0 ? 'A' : 'B') === winningTeam ? 'WIN' : 'LOSS')
      await page.getByText(`${winningTeam} 队赢得对局`, { exact: false }).waitFor({ timeout: 15000 })
    }
    await mobile.done
    assert.deepEqual(pageErrors, [])
    console.log(`PASS: four real identities, three Web browser seats and one ${platform} UI seat; both UIs act and settle; team histories agree${voiceEnabled ? `; ${platform} joined and left LiveKit listen-only without microphone capture` : ''} (${matchId}).`)
  } finally {
    if (mobile?.child.exitCode === null) mobile.child.kill('SIGTERM')
    await Promise.all(contexts.map(context => context.close()))
    await browser.close()
  }
}

main().catch(error => { console.error(error); process.exitCode = 1 })
