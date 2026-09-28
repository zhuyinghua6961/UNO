// Run against a local auth-enabled Web/Gateway/Mailpit stack.
const { chromium } = require('playwright')
const crypto = require('node:crypto')
const assert = require('node:assert/strict')

const origin = process.env.UNO_E2E_ORIGIN ?? 'http://localhost:5173'
const mailpit = process.env.UNO_E2E_MAILPIT ?? 'http://127.0.0.1:28025'
const password = `UnoNetwork-${crypto.randomBytes(16).toString('hex')}`
const suffix = crypto.randomUUID()

async function verificationToken(email) {
  for (let attempt = 0; attempt < 30; attempt++) {
    const list = await (await fetch(`${mailpit}/api/v1/messages`)).json()
    const message = list.messages.find(item => item.To?.some(to => to.Address === email))
    if (message) {
      const detail = await (await fetch(`${mailpit}/api/v1/message/${message.ID}`)).json()
      const token = (detail.Text || '').match(/Token: ([A-Za-z0-9_-]{43})/)?.[1]
      if (token) return token
    }
    await new Promise(resolve => setTimeout(resolve, 500))
  }
  throw new Error(`No verification mail for ${email}`)
}

async function register(page, label) {
  const email = `uno-network-${label.toLowerCase()}-${suffix}@example.test`
  await page.goto(`${origin}/login`)
  await page.getByRole('button', { name: '创建新账号' }).click()
  await page.locator('input[name=email]').fill(email)
  await page.locator('input[name=nickname]').fill(label)
  await page.locator('input[name=password]').fill(password)
  await page.getByRole('button', { name: '注册并申请验证邮件' }).click()
  await page.getByText('请求已受理', { exact: false }).waitFor()
  await page.locator('input[name=token]').fill(await verificationToken(email))
  await page.getByRole('button', { name: '确认验证' }).click()
  await page.getByText('邮箱验证成功', { exact: false }).waitFor()
  await page.locator('input[name=email]').fill(email)
  await page.locator('input[name=password]').fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).last().click()
  await page.waitForURL(`${origin}/account`)
  await page.goto(origin)
}

async function state(page, matchId) {
  return page.evaluate(async id => {
    const response = await fetch(`/api/matches/${id}/state`)
    if (!response.ok) throw new Error(`match state ${response.status}`)
    return response.json()
  }, matchId)
}

async function act(page, matchId) {
  const before = await state(page, matchId)
  const { phase, version } = before.view
  await page.waitForFunction(value => Number(document.querySelector('.live-match')?.getAttribute('data-version')) >= value, version)
  if (phase === 'INITIAL_WILD_COLOR') await page.getByRole('button', { name: '红色' }).first().click()
  else if (phase === 'AFTER_DRAW') await page.getByRole('button', { name: '不出刚摸的牌 · 结束回合' }).click()
  else if (phase === 'DRAW_FOUR_RESPONSE') await page.getByRole('button', { name: '接受 · 摸 4 张' }).click()
  else if (phase === 'TURN') await page.getByRole('button', { name: '摸 1 张' }).click()
  else throw new Error(`Unexpected phase ${phase}`)
  await page.waitForFunction(value => Number(document.querySelector('.live-match')?.getAttribute('data-version')) > value, version)
  return { before: version, after: Number(await page.locator('.live-match').getAttribute('data-version')) }
}

async function main() {
  const browser = await chromium.launch({ headless: true })
  const contexts = await Promise.all([0, 1].map(() => browser.newContext()))
  const pages = await Promise.all(contexts.map(context => context.newPage()))
  const errors = []
  for (const page of pages) page.on('pageerror', error => errors.push(error.message))
  try {
    await register(pages[0], 'NetworkHost')
    await register(pages[1], 'NetworkGuest')
    await pages[0].getByRole('button', { name: '创建好友房' }).click()
    await pages[0].waitForURL(/\/rooms\/[0-9a-f-]+$/)
    const roomId = pages[0].url().split('/').at(-1)
    const code = (await pages[0].locator('.room-code').textContent()).trim()
    await pages[1].locator('input[placeholder="例如 ABCDEFGHJK"]').fill(code)
    await pages[1].getByRole('button', { name: '加入房间' }).click()
    await pages[1].waitForURL(new RegExp(`/rooms/${roomId}$`))
    await pages[0].getByRole('button', { name: '刷新', exact: true }).click()
    await pages[0].waitForFunction(() => document.querySelectorAll('.member-list li').length === 2)
    await pages[0].getByRole('button', { name: '准备', exact: true }).click()
    await pages[0].getByRole('button', { name: '取消准备' }).waitFor()
    await pages[1].getByRole('button', { name: '刷新', exact: true }).click()
    await pages[1].getByRole('button', { name: '准备', exact: true }).click()
    await pages[1].getByRole('button', { name: '取消准备' }).waitFor()
    await pages[0].getByRole('button', { name: '刷新', exact: true }).click()
    await pages[0].waitForFunction(() => { const button = [...document.querySelectorAll('button')].find(el => el.textContent?.trim() === '开始对局'); return button && !button.disabled })
    await pages[0].getByRole('button', { name: '开始对局' }).click()
    await Promise.all(pages.map(page => page.waitForURL(/\/matches\/[0-9a-f-]+$/)))
    const matchId = pages[0].url().split('/').at(-1)
    assert.equal(pages[1].url().split('/').at(-1), matchId)
    await Promise.all(pages.map(page => page.getByText('实时连接').waitFor()))

    const current = await state(pages[0], matchId)
    const actorIndex = current.view.currentSeat
    const offlineIndex = 1 - actorIndex
    const active = pages[actorIndex]
    const recovering = pages[offlineIndex]
    const recoveringContext = contexts[offlineIndex]
    const before = current.view.version
    await recoveringContext.setOffline(true)
    await recovering.locator('.connection').filter({ hasText: '连接中断 · 正在重连' }).waitFor({ timeout: 6000 })
    const move = await act(active, matchId)
    assert.equal(move.before, before)
    assert(move.after > before)
    await recoveringContext.setOffline(false)
    await recovering.getByText('实时连接').waitFor({ timeout: 12000 })
    await recovering.waitForFunction(value => Number(document.querySelector('.live-match')?.getAttribute('data-version')) >= value, move.after)
    const recovered = await state(recovering, matchId)
    assert.equal(Number(await recovering.locator('.live-match').getAttribute('data-version')), recovered.view.version)
    assert.equal(recovered.view.version, (await state(active, matchId)).view.version)

    for (let i = 0; i < 3 && (await state(active, matchId)).view.currentSeat === actorIndex; i++) await act(active, matchId)
    const after = await state(recovering, matchId)
    assert.equal(after.view.currentSeat, offlineIndex, 'turn should reach the recovered browser')
    const resumed = await act(recovering, matchId)
    assert(resumed.after > resumed.before)
    assert.deepEqual(errors, [])
    console.log(`PASS: browser went offline at v${before}, recovered v${recovered.view.version}, then submitted v${resumed.after}`)
  } finally {
    await browser.close()
  }
}

main().catch(error => { console.error(error); process.exitCode = 1 })
