import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { copyFile, mkdir, mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

test('local secrets initialize safely and repeat without changing values', async () => {
  const root = await mkdtemp(join(tmpdir(), 'uno-env-test-'))
  try {
    await mkdir(join(root, 'tools'))
    await mkdir(join(root, 'deploy'))
    const script = join(root, 'tools/init-local-env.mjs')
    const target = join(root, 'deploy/.env')
    await copyFile(new URL('./init-local-env.mjs', import.meta.url), script)
    execFileSync(process.execPath, [script])
    const initial = await readFile(target, 'utf8')
    assert.match(initial, /^IDENTITY_DB_PASSWORD=[0-9a-f]{48}$/m)
    assert.match(initial, /^GAME_DB_PASSWORD=[0-9a-f]{48}$/m)
    assert.equal((await stat(target)).mode & 0o777, 0o600)
    execFileSync(process.execPath, [script])
    assert.equal(await readFile(target, 'utf8'), initial)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test('upgrading an existing env preserves old secrets and custom ports', async () => {
  const root = await mkdtemp(join(tmpdir(), 'uno-env-test-'))
  try {
    await mkdir(join(root, 'tools'))
    await mkdir(join(root, 'deploy'))
    const script = join(root, 'tools/init-local-env.mjs')
    const target = join(root, 'deploy/.env')
    await copyFile(new URL('./init-local-env.mjs', import.meta.url), script)
    const existing = 'WEB_PORT=12345\nexport POSTGRES_PASSWORD="keep-existing-value"\nLIVEKIT_API_SECRET=keep-voice-secret'
    await writeFile(target, existing)
    execFileSync(process.execPath, [script])
    const updated = await readFile(target, 'utf8')
    assert.ok(updated.startsWith(existing + '\n'))
    assert.equal([...updated.matchAll(/POSTGRES_PASSWORD=/g)].length, 1)
    assert.equal([...updated.matchAll(/IDENTITY_DB_PASSWORD=/g)].length, 1)
    assert.match(updated, /^GAME_DB_PASSWORD=[0-9a-f]{48}$/m)
    assert.equal((await stat(target)).mode & 0o777, 0o600)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})
