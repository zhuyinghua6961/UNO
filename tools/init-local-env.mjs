import { randomBytes } from 'node:crypto'
import { appendFile, chmod, readFile, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const target = join(dirname(fileURLToPath(import.meta.url)), '../deploy/.env')
const defaults = {
  WEB_PORT: '8088',
  GATEWAY_PORT: '28080',
  POSTGRES_PORT: '25432',
  POSTGRES_PASSWORD: randomBytes(24).toString('hex'),
  IDENTITY_DB_PASSWORD: randomBytes(24).toString('hex'),
  GAME_DB_PASSWORD: randomBytes(24).toString('hex'),
  LIVEKIT_API_KEY: `uno_${randomBytes(8).toString('hex')}`,
  LIVEKIT_API_SECRET: randomBytes(32).toString('hex'),
}
const content = Object.entries(defaults).map(([key, value]) => `${key}=${value}`).join('\n') + '\n'
try {
  await writeFile(target, content, { flag: 'wx', mode: 0o600 })
  console.log('Created deploy/.env with local random secrets. Do not commit this file.')
} catch (error) {
  if (error.code !== 'EEXIST') throw error
  const existing = await readFile(target, 'utf8')
  const keys = new Set([...existing.matchAll(/^\s*(?:export\s+)?([A-Z_]+)\s*=/gm)].map(match => match[1]))
  const missing = Object.entries(defaults).filter(([key]) => !keys.has(key))
  await chmod(target, 0o600)
  if (missing.length) {
    await appendFile(target, '\n' + missing.map(([key, value]) => `${key}=${value}`).join('\n') + '\n')
  }
  console.log(`Preserved existing values; added ${missing.length} missing settings. Existing database passwords are not rotated.`)
}
