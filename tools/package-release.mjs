import { createHash } from 'node:crypto'
import { access, cp, mkdir, readdir, readFile, writeFile } from 'node:fs/promises'
import { spawnSync } from 'node:child_process'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const label = process.argv[2]
if (!label || !/^[a-zA-Z0-9][a-zA-Z0-9._-]*$/.test(label) || label.includes('..')) {
  throw new Error('Usage: node tools/package-release.mjs <unique-version-label>')
}
const services = ['gateway', 'identity-service', 'game-service']
await access(join(root, 'web/dist/index.html'))
for (const service of services) await access(join(root, `backend/${service}/target/${service}-0.1.0-SNAPSHOT.jar`))
const output = join(root, 'release', label)
await mkdir(output)
await cp(join(root, 'web/dist'), join(output, 'web'), { recursive: true })
await mkdir(join(output, 'backend'))
for (const service of services) {
  await cp(join(root, `backend/${service}/target/${service}-0.1.0-SNAPSHOT.jar`), join(output, 'backend', `${service}.jar`))
}
await mkdir(join(output, 'deploy'))
for (const file of ['README.md', 'compose.yaml', 'livekit.yaml', 'nginx.conf', '.env.example', 'backend.Dockerfile', 'web.Dockerfile']) {
  await cp(join(root, 'deploy', file), join(output, 'deploy', file))
}
const revision = spawnSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' })
const gitStatus = revision.status === 0 ? spawnSync('git', ['status', '--porcelain'], { cwd: root, encoding: 'utf8' }) : null
const manifest = {
  label,
  createdAt: new Date().toISOString(),
  stage: 'scaffold-not-production',
  sourceCommit: revision.status === 0 ? revision.stdout.trim() : null,
  sourceDirty: gitStatus ? gitStatus.stdout.trim().length > 0 : null,
  included: ['web-static', 'backend-jars', 'deployment-source-reference'],
  excluded: ['flutter-apk', 'flutter-ipa', 'docker-images', 'secrets'],
  notes: 'Dockerfiles build from the original source repository; this bundle is not an offline Docker installer. No authentication, chat transport, game engine or voice session is implemented.',
}
await writeFile(join(output, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n')
const sums = []
async function checksum(directory) {
  for (const entry of (await readdir(directory, { withFileTypes: true })).sort((left, right) => left.name.localeCompare(right.name))) {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) await checksum(path)
    else sums.push(`${createHash('sha256').update(await readFile(path)).digest('hex')}  ${relative(output, path)}`)
  }
}
await checksum(output)
await writeFile(join(output, 'SHA256SUMS'), sums.join('\n') + '\n')
const archive = join(root, 'release', `${label}.tar.gz`)
const result = spawnSync('tar', ['-czf', archive, '-C', join(root, 'release'), label], { stdio: 'inherit' })
if (result.status !== 0) throw new Error('Archive creation failed')
await writeFile(`${archive}.sha256`, `${createHash('sha256').update(await readFile(archive)).digest('hex')}  ${label}.tar.gz\n`)
console.log(`Created release/${label}.tar.gz and checksums. This is a scaffold build, not a production release.`)
