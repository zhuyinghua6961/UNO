// Requires a running local Web + identity + game + gateway + Mailpit stack.
// Use UNO_E2E_ORIGIN, UNO_E2E_MAILPIT and UNO_E2E_ARTIFACT_DIR to override defaults.
const { chromium } = require('playwright')
const crypto = require('node:crypto')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')

const origin = process.env.UNO_E2E_ORIGIN ?? 'http://localhost:5173'
const mailpit = process.env.UNO_E2E_MAILPIT ?? 'http://127.0.0.1:28025'
const artifactDir = process.env.UNO_E2E_ARTIFACT_DIR ?? fs.mkdtempSync(path.join(os.tmpdir(), 'uno-classic-e2e-'))
fs.mkdirSync(artifactDir, { recursive: true })
const artifact = name => path.join(artifactDir, name)
const password = `UnoStage8-${crypto.randomBytes(16).toString('hex')}`
const suffix = Date.now()
const users = [
  { email: `uno-stage8-host-${suffix}@example.test`, nickname: 'Stage8房主' },
  { email: `uno-stage8-guest-${suffix}@example.test`, nickname: 'Stage8来客' },
]
const errors = []

async function verificationToken(email) {
  for (let attempt = 0; attempt < 30; attempt++) {
    const list = await (await fetch(`${mailpit}/api/v1/messages`)).json()
    const found = list.messages.find(message => message.To?.some(to => to.Address === email))
    if (found) {
      const detail = await (await fetch(`${mailpit}/api/v1/message/${found.ID}`)).json()
      const token = (detail.Text || '').match(/Token: ([A-Za-z0-9_-]{43})/)?.[1]
      if (token) return token
    }
    await new Promise(resolve => setTimeout(resolve, 500))
  }
  throw new Error(`No verification email for ${email}`)
}

async function register(page, user) {
  await page.goto(`${origin}/login`)
  await page.getByRole('button', { name: '创建新账号' }).click()
  await page.locator('input[name=email]').fill(user.email)
  await page.locator('input[name=nickname]').fill(user.nickname)
  await page.locator('input[name=password]').fill(password)
  await page.getByRole('button', { name: '注册并申请验证邮件' }).click()
  await page.getByText('请求已受理', { exact: false }).waitFor()
  const token = await verificationToken(user.email)
  await page.locator('input[name=token]').fill(token)
  await page.getByRole('button', { name: '确认验证' }).click()
  await page.getByText('邮箱验证成功', { exact: false }).waitFor()
  await page.locator('input[name=email]').fill(user.email)
  await page.locator('input[name=password]').fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).last().click()
  await page.waitForURL(`${origin}/account`)
  await page.getByRole('heading', { name: user.nickname }).waitFor()
  await page.goto(origin)
}

async function main() {
  const browser = await chromium.launch({ headless: true })
  const hostContext = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const guestContext = await browser.newContext({ viewport: { width: 390, height: 844 } })
  const host = await hostContext.newPage()
  const guest = await guestContext.newPage()
  for (const page of [host, guest]) page.on('pageerror', error => errors.push(error.message))
  host.on('response', response => { if (response.url().includes('/start')) console.log('start HTTP', response.status()) })
  try {
    await register(host, users[0])
    await register(guest, users[1])
    console.log('Two real browser accounts registered and logged in')
    await host.getByRole('button', { name: '创建好友房' }).click()
    await host.waitForURL(/\/rooms\/[0-9a-f-]+$/)
    const roomId = host.url().split('/').at(-1)
    const code = (await host.locator('.room-code').textContent()).trim()
    await guest.locator('input[placeholder="例如 ABCDEFGHJK"]').fill(code)
    await guest.getByRole('button', { name: '加入房间' }).click()
    await guest.waitForURL(new RegExp(`/rooms/${roomId}$`))
    await host.getByRole('button', { name: '刷新', exact: true }).click()
    await host.waitForFunction(() => document.querySelectorAll('.member-list li').length === 2)
    await host.getByRole('button', { name: '准备', exact: true }).click()
    await host.getByRole('button', { name: '取消准备' }).waitFor()
    await guest.getByRole('button', { name: '刷新', exact: true }).click()
    await guest.waitForFunction(() => document.querySelector('.member-list')?.textContent?.includes('已准备'))
    await guest.getByRole('button', { name: '准备', exact: true }).click()
    await guest.getByRole('button', { name: '取消准备' }).waitFor()
    await host.getByRole('button', { name: '刷新', exact: true }).click()
    await host.waitForFunction(() => { const button = [...document.querySelectorAll('button')].find(el => el.textContent?.trim() === '开始对局'); return button && !button.disabled })
    await host.getByRole('button', { name: '开始对局' }).click()
    await host.waitForURL(/\/matches\/[0-9a-f-]+$/, { timeout: 8000 }).catch(async failure => {
      console.log('start failure', host.url(), await host.locator('[role=alert]').allTextContents(), await host.locator('.room-panel').allTextContents())
      throw failure
    })
    await guest.waitForURL(/\/matches\/[0-9a-f-]+$/, { timeout: 15000 })
    const matchId = host.url().split('/').at(-1)
    assert.equal(guest.url().split('/').at(-1), matchId)
    await host.getByText('实时连接').waitFor()
    await guest.getByText('实时连接').waitFor()
    assert((await host.locator('.hand-card').count()) >= 7)
    assert((await guest.locator('.hand-card').count()) >= 7)
    console.log('Two browsers entered the same live match')
    const actor = await host.getByText('轮到你', { exact: true }).count() ? host : guest
    if (await actor.getByText('选择开局颜色').count()) {
      await actor.getByRole('button', { name: '红色' }).first().click()
    } else {
      await actor.getByRole('button', { name: '摸 1 张' }).click()
    }
    await actor.getByText('操作已由服务器确认。').waitFor()
    const [hostState, guestState] = await Promise.all([host, guest].map(page => page.evaluate(async id => {
      const response = await fetch(`/api/matches/${id}/state`, { credentials: 'same-origin' })
      if (!response.ok) throw new Error(`state ${response.status}`)
      return response.json()
    }, matchId)))
    assert.equal(hostState.view.version, guestState.view.version)
    assert(hostState.view.version >= 2)
    assert.notDeepEqual(hostState.view.ownHand, guestState.view.ownHand)
    console.log(`Server version ${hostState.view.version} visible to both; private hands differ`)
    let actions = 0
    let rounds = 0
    while (actions < 2500) {
      const hostRound = await host.evaluate(async id => (await fetch(`/api/matches/${id}/state`)).json(), matchId)
      if (hostRound.view.phase === 'MATCH_OVER') break
      if (hostRound.view.phase === 'ROUND_OVER') {
        await Promise.all([host, guest].map(page => page.waitForFunction(value => Number(document.querySelector('.live-match')?.getAttribute('data-version')) >= value, hostRound.view.version)))
        console.log('Round over', JSON.stringify({ version: hostRound.view.version, points: hostRound.view.roundPoints,
          hostStatus: await host.locator('.connection').textContent(), guestStatus: await guest.locator('.connection').textContent() }))
        await host.getByRole('button', { name: '开始下一轮' }).click()
        try {
          await Promise.all([host, guest].map(page => page.waitForFunction(value => Number(document.querySelector('.live-match')?.getAttribute('data-version')) > value, hostRound.view.version, { timeout: 6000 })))
        } catch (failure) {
          console.log('round stalled', JSON.stringify({ hostVersion: await host.locator('.live-match').getAttribute('data-version'),
            guestVersion: await guest.locator('.live-match').getAttribute('data-version'),
            hostStatus: await host.locator('.connection').textContent(), guestStatus: await guest.locator('.connection').textContent(),
            hostAlert: await host.locator('[role=alert]').allTextContents(), guestAlert: await guest.locator('[role=alert]').allTextContents(),
            hostNotice: await host.locator('[role=status]').allTextContents() }))
          const latest = await host.evaluate(async id => (await fetch(`/api/matches/${id}/state`)).json(), matchId)
          if (latest.view.phase === 'ROUND_OVER') continue
          await host.screenshot({ path: artifact('round-stalled.png'), fullPage: true })
          throw failure
        }
        rounds++
        continue
      }
      const current = await host.getByText('轮到你', { exact: true }).count() ? host : guest
      const playerState = await current.evaluate(async id => (await fetch(`/api/matches/${id}/state`)).json(), matchId)
      const view = playerState.view
      const version = view.version
      await current.waitForFunction(value => Number(document.querySelector('.live-match')?.getAttribute('data-version')) >= value, version)
      if (view.phase === 'INITIAL_WILD_COLOR') {
        await current.getByRole('button', { name: '红色' }).first().click()
      } else if (view.phase === 'DRAW_FOUR_RESPONSE') {
        await current.getByRole('button', { name: '接受 · 摸 4 张' }).click()
      } else {
        const playable = card => card.color === null || card.color === view.activeColor ||
          (view.topCard.color !== null && card.kind === view.topCard.kind &&
            (card.kind !== 'NUMBER' || card.number === view.topCard.number))
        const index = view.phase === 'AFTER_DRAW'
          ? view.ownHand.findIndex(card => card.id === view.drawnCardId)
          : view.ownHand.findIndex(playable)
        if (index < 0) await current.getByRole('button', { name: '摸 1 张' }).click()
        else {
          const card = view.ownHand[index]
          await current.locator('.hand-card').nth(index).click()
          if (card.color === null) await current.locator('.color-choices .red').click()
          if (view.ownHand.length === 2) await current.locator('.uno-check input').check()
          await current.getByRole('button', { name: '打出选中的牌' }).click()
        }
      }
      try {
        await Promise.all([host, guest].map(page => page.waitForFunction(value => Number(document.querySelector('.live-match')?.getAttribute('data-version')) > value, version, { timeout: 4500 })))
      } catch (failure) {
        console.log('stalled', JSON.stringify({ actions, version, phase: view.phase, currentSeat: view.currentSeat,
          hand: view.ownHand.length, hostVersion: await host.locator('.live-match').getAttribute('data-version'),
          guestVersion: await guest.locator('.live-match').getAttribute('data-version'),
          hostAlert: await host.locator('[role=alert]').allTextContents(), guestAlert: await guest.locator('[role=alert]').allTextContents() }))
        const latest = await current.evaluate(async id => (await fetch(`/api/matches/${id}/state`)).json(), matchId)
        if (latest.view.version === version && await current.locator('.connection').textContent() === '实时连接') {
          console.log('Retrying unconfirmed action after connection recovered')
          continue
        }
        await current.screenshot({ path: artifact('action-stalled.png'), fullPage: true })
        throw failure
      }
      actions++
      if (actions % 10 === 0) console.log(`Autoplay actions ${actions}`)
      await new Promise(resolve => setTimeout(resolve, 350))
    }
    const completed = await host.evaluate(async id => (await fetch(`/api/matches/${id}/state`)).json(), matchId)
    assert(actions < 2500)
    assert.equal(completed.view.phase, 'MATCH_OVER')
    console.log(`Two real browser accounts completed ${rounds + 1} rounds and a match in ${actions} UI actions`)
    await host.screenshot({ path: artifact('settlement-desktop.png'), fullPage: true })
    await guest.screenshot({ path: artifact('settlement-mobile.png'), fullPage: true })
    await host.getByRole('link', { name: '返回等待室 · 再来一局' }).click()
    await guest.getByRole('link', { name: '返回等待室 · 再来一局' }).click()
    await host.waitForURL(new RegExp(`/rooms/${roomId}$`))
    await guest.waitForURL(new RegExp(`/rooms/${roomId}$`))
    await host.getByRole('button', { name: '准备', exact: true }).click()
    await host.getByRole('button', { name: '取消准备' }).waitFor()
    await guest.getByRole('button', { name: '刷新', exact: true }).click()
    await guest.waitForFunction(() => document.querySelector('.member-list')?.textContent?.includes('已准备'))
    await guest.getByRole('button', { name: '准备', exact: true }).click()
    await guest.getByRole('button', { name: '取消准备' }).waitFor()
    await host.getByRole('button', { name: '刷新', exact: true }).click()
    await host.waitForFunction(() => { const button = [...document.querySelectorAll('button')].find(el => el.textContent?.trim() === '开始对局'); return button && !button.disabled })
    await host.getByRole('button', { name: '开始对局' }).click()
    await host.waitForURL(/\/matches\/[0-9a-f-]+$/)
    await guest.waitForURL(/\/matches\/[0-9a-f-]+$/, { timeout: 15000 })
    const nextMatchId = host.url().split('/').at(-1)
    assert.notEqual(nextMatchId, matchId)
    assert.equal(guest.url().split('/').at(-1), nextMatchId)
    await host.getByText('实时连接').waitFor()
    await guest.getByText('实时连接').waitFor()
    await host.screenshot({ path: artifact('next-match-desktop.png'), fullPage: true })
    await guest.screenshot({ path: artifact('next-match-mobile.png'), fullPage: true })
    const overflow = await guest.evaluate(() => document.documentElement.scrollWidth > innerWidth)
    assert.equal(overflow, false)
    await host.reload()
    await host.getByText('实时连接').waitFor()
    assert((await host.locator('.hand-card').count()) >= 7)
    console.log('Second match started; refresh recovery and narrow layout passed')
    assert.deepEqual(errors, [])
    console.log('Refresh recovery, narrow layout, and page scripts passed')
    console.log(`Screenshots: ${artifactDir}`)
  } finally {
    await browser.close()
  }
}
main().catch(error => { console.error(error.stack || error.message); process.exit(1) })
