import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'

const api = process.env.API_BASE_URL ?? 'http://127.0.0.1:28080'
const mailpit = process.env.MAILPIT_BASE_URL ?? 'http://127.0.0.1:28025'
const clientHeaders = { 'X-UNO-Client': 'APP' }

async function request(path, { method = 'GET', token, body } = {}) {
  const response = await fetch(`${api}${path}`, {
    method,
    headers: {
      ...clientHeaders,
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
  const content = response.headers.get('content-type')?.includes('application/json')
    ? await response.json() : undefined
  return { status: response.status, body: content }
}

async function verificationToken(email) {
  for (let attempt = 0; attempt < 30; attempt++) {
    const response = await fetch(`${mailpit}/api/v1/messages`)
    assert.equal(response.status, 200, 'Mailpit must be available for this local smoke test')
    const listing = await response.json()
    const message = listing.messages.find(item => item.To?.some(to => to.Address === email))
    if (message) {
      const detailResponse = await fetch(`${mailpit}/api/v1/message/${encodeURIComponent(message.ID)}`)
      assert.equal(detailResponse.status, 200)
      const detail = await detailResponse.json()
      const token = detail.Text?.match(/^Token: ([A-Za-z0-9_-]{43})$/m)?.[1]
      assert.ok(token, 'Verification email must contain a token')
      return token
    }
    await new Promise(resolve => setTimeout(resolve, 500))
  }
  throw new Error('Verification email was not delivered to Mailpit within 15 seconds')
}

async function account(label) {
  const email = `uno-smoke-${randomUUID()}@example.test`
  const password = `Smoke-${randomUUID()}-Pass1!`
  const registered = await request('/api/auth/register', {
    method: 'POST', body: { email, password, nickname: label },
  })
  assert.equal(registered.status, 202, `${label} registration`)
  const verified = await request('/api/auth/verify-email', {
    method: 'POST', body: { token: await verificationToken(email) },
  })
  assert.equal(verified.status, 204, `${label} verification`)
  const loggedIn = await request('/api/auth/login', {
    method: 'POST', body: { email, password },
  })
  assert.equal(loggedIn.status, 200, `${label} login`)
  assert.ok(loggedIn.body.accessToken)
  return loggedIn.body.accessToken
}

const host = await account('Smoke Host')
const guest = await account('Smoke Guest')
const renamed = await request('/api/users/me/profile', {
  method: 'POST', token: host, body: { nickname: 'Smoke Host Renamed' },
})
assert.equal(renamed.status, 200, 'authenticated profile update')
assert.equal(renamed.body.nickname, 'Smoke Host Renamed')
assert.equal((await request('/api/users/me', { token: host })).body.nickname,
  'Smoke Host Renamed', 'same App session sees updated profile')
const newHistory = await request('/api/matches/history', { token: host })
assert.equal(newHistory.status, 200, 'authenticated personal history')
assert.deepEqual(newHistory.body.items, [])
assert.deepEqual((await request('/api/matches/stats', { token: host })).body, {
  classic: { wins: 0, losses: 0, interrupted: 0 },
  team2v2: { wins: 0, losses: 0, interrupted: 0 },
})
assert.equal((await request('/api/matches/stats')).status, 401)
const created = await request('/api/rooms', {
  method: 'POST', token: host, body: { mode: 'CLASSIC', maxPlayers: 2 },
})
assert.equal(created.status, 201, 'room creation')
const room = created.body
assert.ok(room.id && room.code)
assert.equal(room.members[0].nickname, 'Smoke Host Renamed')
const joined = await request('/api/rooms/join', {
  method: 'POST', token: guest, body: { code: room.code },
})
assert.equal(joined.status, 200, 'room join')
assert.equal(joined.body.id, room.id)

const firstId = randomUUID()
const first = await request(`/api/rooms/${room.id}/messages`, {
  method: 'POST', token: host, body: { clientMessageId: firstId, content: 'Container smoke: hello' },
})
assert.equal(first.status, 200, 'host message')
const repeated = await request(`/api/rooms/${room.id}/messages`, {
  method: 'POST', token: host, body: { clientMessageId: firstId, content: 'Container smoke: hello' },
})
assert.equal(repeated.status, 200, 'idempotent message retry')
assert.equal(repeated.body.id, first.body.id)
const second = await request(`/api/rooms/${room.id}/messages`, {
  method: 'POST', token: guest, body: { clientMessageId: randomUUID(), content: 'Container smoke: received' },
})
assert.equal(second.status, 200, 'guest message')
assert.ok(second.body.sequence > first.body.sequence)
const history = await request(`/api/rooms/${room.id}/messages`, { token: guest })
assert.equal(history.status, 200, 'guest history')
assert.deepEqual(history.body.items.map(item => item.id), [first.body.id, second.body.id])

if (process.env.SMOKE_FULL_MATCH === 'true') {
  let waiting = await request(`/api/rooms/${room.id}`, { token: host })
  assert.equal(waiting.status, 200)
  for (const token of [host, guest]) {
    waiting = await request(`/api/rooms/${room.id}/ready`, {
      method: 'POST', token, body: { ready: true, expectedVersion: waiting.body.version },
    })
    assert.equal(waiting.status, 200, 'player ready')
  }
  const started = await request(`/api/rooms/${room.id}/start`, {
    method: 'POST', token: host, body: { expectedVersion: waiting.body.version },
  })
  assert.equal(started.status, 200, 'classic match start')
  const matchId = started.body.matchId
  const hostId = (await request('/api/users/me', { token: host })).body.id
  const guestId = (await request('/api/users/me', { token: guest })).body.id
  const tokens = new Map([[hostId, host], [guestId, guest]])
  let settled = false
  for (let actionNumber = 0; actionNumber < 2500; actionNumber++) {
    assert.ok(actionNumber < 2499, 'classic match must finish within 2500 actions')
    const hostState = await request(`/api/matches/${matchId}/state`, { token: host })
    assert.equal(hostState.status, 200, 'host private state')
    const publicView = hostState.body.view
    if (publicView.phase === 'MATCH_OVER') { settled = true; break }
    const actorId = publicView.players[publicView.currentSeat].userId
    const actorToken = publicView.phase === 'ROUND_OVER' ? host : tokens.get(actorId)
    assert.ok(actorToken, 'current actor must have a test credential')
    const actorState = actorToken === host ? hostState
      : await request(`/api/matches/${matchId}/state`, { token: actorToken })
    assert.equal(actorState.status, 200, 'actor private state')
    const view = actorState.body.view
    const action = { protocolVersion: 1, commandId: randomUUID(), expectedVersion: view.version }
    if (view.phase === 'ROUND_OVER') action.type = 'NEXT_ROUND'
    else if (view.phase === 'INITIAL_WILD_COLOR') {
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
    } else throw new Error(`Unexpected match phase: ${view.phase}`)
    if (action.type === 'PLAY') {
      const card = view.ownHand.find(item => item.id === action.cardId)
      assert.ok(card)
      if (card.color === null) action.chosenColor = 'RED'
      action.callUno = view.ownHand.length === 2
    }
    const applied = await request(`/api/matches/${matchId}/commands`, {
      method: 'POST', token: actorToken, body: action,
    })
    if (applied.status === 409 && ['MATCH_CONFLICT', 'TURN_EXPIRED'].includes(applied.body?.code)) continue
    assert.equal(applied.status, 200, `classic action ${action.type}: ${applied.body?.code ?? ''}`)
  }
  assert.ok(settled, 'classic match must settle')
  const hostResult = await request('/api/matches/history', { token: host })
  const guestResult = await request('/api/matches/history', { token: guest })
  assert.equal(hostResult.status, 200)
  assert.equal(guestResult.status, 200)
  assert.equal(hostResult.body.items[0].matchId, matchId)
  assert.equal(guestResult.body.items[0].matchId, matchId)
  assert.notEqual(hostResult.body.items[0].result, guestResult.body.items[0].result)
  assert.ok(!JSON.stringify(hostResult.body).includes('ownHand'))
  for (const [token, result] of [[host, hostResult], [guest, guestResult]]) {
    const stats = await request('/api/matches/stats', { token })
    assert.equal(stats.status, 200)
    assert.equal(stats.body.classic.wins, result.body.items[0].result === 'WIN' ? 1 : 0)
    assert.equal(stats.body.classic.losses, result.body.items[0].result === 'LOSS' ? 1 : 0)
    assert.equal(stats.body.classic.interrupted, 0)
  }
  console.log('PASS: containerized classic match settles and both private history results agree.')
}

const left = await request(`/api/rooms/${room.id}/leave`, { method: 'POST', token: guest })
assert.equal(left.status, 204, 'guest leave')
const afterLeave = await request(`/api/rooms/${room.id}/messages`, { token: guest })
assert.equal(afterLeave.status, 404, 'former member must lose chat access')

console.log('PASS: containerized registration, Mailpit verification, App login, profile update, empty personal history, room join, bidirectional text, idempotency, and leave access.')

if (process.env.SMOKE_TEAM_MATCH === 'true') {
  const teamTokens = await Promise.all(['A1', 'B1', 'A2', 'B2'].map(label => account(`Smoke ${label}`)))
  const teamUsers = await Promise.all(teamTokens.map(async token => {
    const response = await request('/api/users/me', { token })
    assert.equal(response.status, 200)
    return response.body.id
  }))
  let waiting = await request('/api/rooms', {
    method: 'POST', token: teamTokens[0], body: { mode: 'TEAM_2V2', maxPlayers: 4 },
  })
  assert.equal(waiting.status, 201, 'team room creation')
  const teamRoomId = waiting.body.id
  for (const token of teamTokens.slice(1)) {
    waiting = await request('/api/rooms/join', {
      method: 'POST', token, body: { code: waiting.body.code },
    })
    assert.equal(waiting.status, 200, 'team player join')
  }
  assert.deepEqual(waiting.body.members.map(member => member.team), ['A', 'B', 'A', 'B'])
  const teamAId = randomUUID()
  const sentA = await request(`/api/rooms/${teamRoomId}/messages`, {
    method: 'POST', token: teamTokens[0],
    body: { clientMessageId: teamAId, channel: 'TEAM', content: 'Only A may read this' },
  })
  assert.equal(sentA.status, 200, 'A team message')
  assert.equal(sentA.body.channel, 'TEAM_A')
  const sentB = await request(`/api/rooms/${teamRoomId}/messages`, {
    method: 'POST', token: teamTokens[1],
    body: { clientMessageId: randomUUID(), channel: 'TEAM', content: 'Only B may read this' },
  })
  assert.equal(sentB.status, 200, 'B team message')
  assert.equal(sentB.body.channel, 'TEAM_B')
  const teamHistory = await Promise.all(teamTokens.map(token => request(
    `/api/rooms/${teamRoomId}/messages?channel=TEAM&latest=true`, { token },
  )))
  assert.ok(teamHistory.every(result => result.status === 200))
  assert.deepEqual(teamHistory.map(result => result.body.items.map(item => item.id)),
    [[sentA.body.id], [sentB.body.id], [sentA.body.id], [sentB.body.id]])
  const forgedTeam = await request(`/api/rooms/${teamRoomId}/messages`, {
    method: 'POST', token: teamTokens[1],
    body: { clientMessageId: randomUUID(), channel: 'TEAM_A', content: 'forged audience' },
  })
  assert.equal(forgedTeam.status, 400, 'client cannot name the other team channel')
  for (const token of teamTokens) {
    waiting = await request(`/api/rooms/${teamRoomId}/ready`, {
      method: 'POST', token, body: { ready: true, expectedVersion: waiting.body.version },
    })
    assert.equal(waiting.status, 200, 'team player ready')
  }
  const started = await request(`/api/rooms/${teamRoomId}/start`, {
    method: 'POST', token: teamTokens[0], body: { expectedVersion: waiting.body.version },
  })
  assert.equal(started.status, 200, `team start: ${started.body?.code ?? ''}`)
  const teamMatchId = started.body.matchId
  if (process.env.SMOKE_TEAM_VOICE === 'true') {
    const voiceTokens = await Promise.all(teamTokens.map((token, seat) => request('/api/voice/token', {
      method: 'POST', token,
      body: { matchId: teamMatchId, ...(seat === 1 ? { teamId: 'A', roomName: 'forged' } : {}) },
    })))
    assert.ok(voiceTokens.every(result => result.status === 200),
      `voice token issuance: ${voiceTokens.map(result => result.body?.code ?? result.status).join(',')}`)
    const claims = voiceTokens.map(result => JSON.parse(Buffer.from(result.body.token.split('.')[1], 'base64url')))
    const voiceRooms = claims.map(item => item.video.room)
    assert.equal(voiceRooms[0], voiceRooms[2], 'A teammates share one media room')
    assert.equal(voiceRooms[1], voiceRooms[3], 'B teammates share one media room')
    assert.notEqual(voiceRooms[0], voiceRooms[1], 'opponents have separate media rooms')
    for (const [seat, item] of claims.entries()) {
      assert.equal(item.sub, teamUsers[seat])
      assert.equal(item.video.roomJoin, true)
      assert.deepEqual(item.video.canPublishSources, ['microphone'])
      assert.equal(item.video.canPublishData, false)
      assert.equal(item.video.roomCreate ?? false, false)
      assert.ok(item.exp - Math.floor(Date.now() / 1000) <= 61, 'voice token is short lived')
    }
    const outsider = await request('/api/voice/token', {
      method: 'POST', token: host, body: { matchId: teamMatchId },
    })
    assert.equal(outsider.status, 404, 'nonmember cannot receive voice token')
    console.log('PASS: four authenticated voice grants are microphone only and isolated by team.')
  }
  const byId = new Map(teamUsers.map((id, seat) => [id, teamTokens[seat]]))
  let finalView
  for (let actionNumber = 0; actionNumber < 800; actionNumber++) {
    const hostState = await request(`/api/matches/${teamMatchId}/state`, { token: teamTokens[0] })
    assert.equal(hostState.status, 200, 'team host state')
    const publicView = hostState.body.view
    if (publicView.phase === 'MATCH_OVER') { finalView = publicView; break }
    assert.notEqual(publicView.phase, 'ROUND_OVER', 'team game finishes in one round')
    const actorToken = byId.get(publicView.players[publicView.currentSeat].userId)
    assert.ok(actorToken, 'team actor has credential')
    const actorState = actorToken === teamTokens[0] ? hostState
      : await request(`/api/matches/${teamMatchId}/state`, { token: actorToken })
    assert.equal(actorState.status, 200, 'team actor state')
    const view = actorState.body.view
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
    } else throw new Error(`Unexpected team phase: ${view.phase}`)
    if (action.type === 'PLAY') {
      const card = view.ownHand.find(item => item.id === action.cardId)
      assert.ok(card)
      if (card.color === null) action.chosenColor = 'RED'
      action.callUno = view.ownHand.length === 2
    }
    const applied = await request(`/api/matches/${teamMatchId}/commands`, {
      method: 'POST', token: actorToken, body: action,
    })
    if (applied.status === 409 && ['MATCH_CONFLICT', 'TURN_EXPIRED'].includes(applied.body?.code)) continue
    assert.equal(applied.status, 200, `team action ${action.type}: ${applied.body?.code ?? ''}`)
  }
  assert.ok(finalView, 'team match must finish within 800 actions')
  const winnerTeam = finalView.roundWinnerSeat % 2 === 0 ? 'A' : 'B'
  assert.equal(finalView.players[0].score, finalView.players[2].score)
  assert.equal(finalView.players[1].score, finalView.players[3].score)
  for (let seat = 0; seat < 4; seat++) {
    const result = await request('/api/matches/history', { token: teamTokens[seat] })
    assert.equal(result.status, 200)
    assert.equal(result.body.items[0].matchId, teamMatchId)
    assert.equal(result.body.items[0].mode, 'TEAM_2V2')
    assert.equal(result.body.items[0].result,
      (seat % 2 === 0 ? 'A' : 'B') === winnerTeam ? 'WIN' : 'LOSS')
    assert.ok(!JSON.stringify(result.body).includes('ownHand'))
    const stats = await request('/api/matches/stats', { token: teamTokens[seat] })
    assert.equal(stats.status, 200)
    assert.equal(stats.body.team2v2.wins, result.body.items[0].result === 'WIN' ? 1 : 0)
    assert.equal(stats.body.team2v2.losses, result.body.items[0].result === 'LOSS' ? 1 : 0)
    assert.equal(stats.body.team2v2.interrupted, 0)
  }
  const returned = await request(`/api/rooms/${teamRoomId}`, { token: teamTokens[0] })
  assert.equal(returned.status, 200)
  assert.equal(returned.body.state, 'WAITING')
  assert.ok(returned.body.members.every(member => !member.ready))
  if (process.env.SMOKE_TEAM_VOICE === 'true') {
    const ended = await request('/api/voice/token', {
      method: 'POST', token: teamTokens[0], body: { matchId: teamMatchId },
    })
    assert.equal(ended.status, 404, 'ended match cannot issue voice token')
  }
  console.log(`PASS: four real accounts have isolated team text and finish match; ${winnerTeam} wins, histories agree, room resets.`)
}
