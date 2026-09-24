import { createHash } from 'node:crypto'
import { access, cp, mkdir, readdir, readFile, writeFile } from 'node:fs/promises'
import { spawnSync } from 'node:child_process'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const label = process.argv[2]
if (process.argv.length !== 3 || !label || !/^[a-zA-Z0-9][a-zA-Z0-9._-]*$/.test(label) || label.includes('..')) {
  throw new Error('Usage: node tools/package-release.mjs <unique-version-label>')
}
const services = ['gateway', 'identity-service', 'game-service']
const output = join(root, 'release', label)
const archive = join(root, 'release', `${label}.tar.gz`)
const checksumFile = `${archive}.sha256`
async function requireAbsent(path) {
  try { await access(path); throw new Error(`Release output already exists: ${path}`) }
  catch (error) { if (error.code !== 'ENOENT') throw error }
}
await Promise.all([output, archive, checksumFile].map(requireAbsent))

function git(args) {
  const result = spawnSync('git', args, { cwd: root, encoding: 'utf8' })
  if (result.status !== 0) throw new Error(`Git ${args.join(' ')} failed: ${result.stderr.trim()}`)
  return result.stdout.trim()
}
const revision = git(['rev-parse', 'HEAD'])
function requireCleanSource() {
  if (git(['rev-parse', 'HEAD']) !== revision || git(['status', '--porcelain']).length > 0) {
    throw new Error('Commit all source changes before packaging; the source revision must stay unchanged during the build')
  }
}
function run(command, args) {
  const result = spawnSync(command, args, { cwd: root, stdio: 'inherit' })
  if (result.status !== 0) throw new Error(`${command} ${args.join(' ')} failed`)
}
requireCleanSource()
run('npm', ['--prefix', 'web', 'test'])
run('npm', ['--prefix', 'web', 'run', 'build'])
run('mvn', ['-f', 'backend/pom.xml', 'clean', 'verify'])
requireCleanSource()
await access(join(root, 'web/dist/index.html'))
for (const service of services) await access(join(root, `backend/${service}/target/${service}-0.1.0-SNAPSHOT.jar`))

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
const manifest = {
  label,
  createdAt: new Date().toISOString(),
  stage: 'local-preview-not-production',
  sourceCommit: revision,
  sourceDirty: false,
  validation: ['npm --prefix web test', 'npm --prefix web run build', 'mvn -f backend/pom.xml clean verify'],
  included: ['web-static', 'backend-jars', 'deployment-source-reference'],
  excluded: ['flutter-apk', 'flutter-ipa', 'docker-images', 'secrets'],
  notes: 'Local preview only. Dockerfiles require the original source repository; this bundle is not an offline Docker installer. Database integration, browser/device end-to-end, signed mobile builds, and production deployment are separate gates.',
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
const result = spawnSync('tar', ['-czf', archive, '-C', join(root, 'release'), label], { stdio: 'inherit' })
if (result.status !== 0) throw new Error('Archive creation failed')
await writeFile(checksumFile, `${createHash('sha256').update(await readFile(archive)).digest('hex')}  ${label}.tar.gz\n`)
console.log(`Created release/${label}.tar.gz and checksums from ${revision}. This is a local preview, not a production release.`)
