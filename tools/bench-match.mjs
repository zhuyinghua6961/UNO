import assert from 'node:assert/strict'
import { spawn, execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { fileURLToPath } from 'node:url'
import { dirname, resolve as pathResolve } from 'node:path'

const exec = promisify(execFile)
const root = pathResolve(dirname(fileURLToPath(import.meta.url)), '..')

function loopback(name) {
  const raw = process.env[name]
  assert.ok(raw, `${name} is required`)
  const url = new URL(raw)
  assert.equal(url.protocol, 'http:', `${name} must use local HTTP`)
  assert.ok(['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname),
    `${name} must point to loopback`)
  assert.equal(url.pathname, '/', `${name} must be an origin without a path`)
  return url.origin
}

function positiveInteger(name, fallback, maximum) {
  const value = Number(process.env[name] ?? fallback)
  assert.ok(Number.isSafeInteger(value) && value > 0 && value <= maximum,
    `${name} must be an integer from 1 to ${maximum}`)
  return value
}

function mebibytes(raw) {
  const match = raw.match(/^([\d.]+)\s*([KMGTP]i?B)\s*\//)
  assert.ok(match, `Unexpected Docker memory usage: ${raw}`)
  const powers = { KiB: 1, MiB: 2, GiB: 3, TiB: 4, PiB: 5,
    KB: 1, MB: 2, GB: 3, TB: 4, PB: 5 }
  const base = match[2].includes('i') ? 1024 : 1000
  return Number(match[1]) * (base ** powers[match[2]]) / (1024 ** 2)
}

const api = loopback('API_BASE_URL')
const mailpit = loopback('MAILPIT_BASE_URL')
const suites = positiveInteger('BENCH_SUITES', 2, 4)
const interval = positiveInteger('BENCH_SAMPLE_INTERVAL_MS', 1000, 10000)
const suiteTimeoutSeconds = positiveInteger('BENCH_SUITE_TIMEOUT_SECONDS', 240, 600)
const project = process.env.BENCH_COMPOSE_PROJECT
assert.match(project ?? '', /^[a-z][a-z0-9_-]{0,63}$/,
  'BENCH_COMPOSE_PROJECT must name an existing local Compose project')

const { stdout: idsOutput } = await exec('docker', [
  'ps', '--filter', `label=com.docker.compose.project=${project}`, '--format', '{{.ID}}',
])
const ids = idsOutput.trim().split('\n').filter(Boolean)
assert.ok(ids.length >= 3, 'Compose project must have at least three running containers')

const samples = []
let pendingSample
let sampleError
async function sample() {
  if (pendingSample || sampleError) return
  pendingSample = (async () => {
    const { stdout } = await exec('docker', [
      'stats', '--no-stream', '--format', '{{json .}}', ...ids,
    ], { timeout: 10000 })
    const containers = stdout.trim().split('\n').filter(Boolean).map(line => {
      const item = JSON.parse(line)
      return {
        name: item.Name,
        cpuPercent: Number(item.CPUPerc.replace('%', '')),
        memoryMiB: Number(mebibytes(item.MemUsage).toFixed(1)),
      }
    })
    assert.equal(containers.length, ids.length, 'Docker stats omitted a container')
    samples.push({ elapsedSeconds: Number(((performance.now() - started) / 1000).toFixed(2)),
      containers })
  })().catch(error => { sampleError = error }).finally(() => { pendingSample = undefined })
  await pendingSample
}

function runSuite(index) {
  return new Promise(done => {
    const began = performance.now()
    const child = spawn(process.execPath, [pathResolve(root, 'tools/smoke-auth-chat.mjs')], {
      cwd: root,
      env: {
        ...process.env,
        API_BASE_URL: api,
        MAILPIT_BASE_URL: mailpit,
        SMOKE_FULL_MATCH: 'true',
        SMOKE_TEAM_MATCH: 'true',
        SMOKE_CHAT_WS: 'false',
        SMOKE_TEAM_VOICE: 'false',
        SMOKE_CHAT_MODERATION: 'false',
        SMOKE_MULTI_INSTANCE: 'false',
        SMOKE_GAME_CRASH: 'false',
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    let output = ''
    let errors = ''
    let timedOut = false
    const timeout = setTimeout(() => {
      timedOut = true
      child.kill('SIGTERM')
    }, suiteTimeoutSeconds * 1000)
    child.stdout.on('data', data => { output += data.toString() })
    child.stderr.on('data', data => { errors += data.toString() })
    child.on('error', error => {
      clearTimeout(timeout)
      done({ index, passed: false, error: error.message })
    })
    child.on('close', code => {
      clearTimeout(timeout)
      done({
        index,
        passed: !timedOut && code === 0 && output.includes('containerized classic match settles')
          && output.includes('four real accounts have isolated team text and finish match'),
        elapsedSeconds: Number(((performance.now() - began) / 1000).toFixed(2)),
        ...(code === 0 ? {} : { exitCode: code }),
        ...(timedOut ? { error: 'suite timed out' }
          : code === 0 ? {} : { error: errors.slice(-1000) }),
      })
    })
  })
}

const started = performance.now()
await sample()
assert.ifError(sampleError)
const timer = setInterval(() => { void sample() }, interval)
const results = await Promise.all(Array.from({ length: suites }, (_, index) => runSuite(index + 1)))
clearInterval(timer)
if (pendingSample) await pendingSample
await sample()
assert.ifError(sampleError)

const peakByContainer = {}
for (const entry of samples) {
  for (const container of entry.containers) {
    const peak = peakByContainer[container.name] ?? { cpuPercent: 0, memoryMiB: 0 }
    peak.cpuPercent = Math.max(peak.cpuPercent, container.cpuPercent)
    peak.memoryMiB = Math.max(peak.memoryMiB, container.memoryMiB)
    peakByContainer[container.name] = peak
  }
}
const result = {
  target: api,
  composeProject: project,
  scenario: 'parallel real accounts; each suite completes classic and 2v2 matches, room text and histories',
  suites,
  elapsedSeconds: Number(((performance.now() - started) / 1000).toFixed(2)),
  suiteResults: results,
  resourceSamples: samples.length,
  peakByContainer,
}
console.log(JSON.stringify(result, null, 2))
assert.ok(results.every(item => item.passed), 'one or more full-match suites failed')
