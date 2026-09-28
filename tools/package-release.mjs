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
const includeIosSimulator = process.env.PACKAGE_IOS_SIMULATOR === 'true'
if (includeIosSimulator && process.platform !== 'darwin') {
  throw new Error('An iOS Simulator app can only be built on macOS with Xcode')
}
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
function run(command, args, cwd = root) {
  const result = spawnSync(command, args, { cwd, stdio: 'inherit' })
  if (result.status !== 0) throw new Error(`${command} ${args.join(' ')} failed`)
}
requireCleanSource()
if (includeIosSimulator) run('xcodebuild', ['-version'])
run('node', ['tools/sync-assets.mjs'])
run('npm', ['--prefix', 'web', 'test'])
run('npm', ['--prefix', 'web', 'run', 'build'])
run('mvn', ['-f', 'backend/pom.xml', '-Pdatabase-it', 'clean', 'verify'])
run('dart', ['analyze', 'lib', 'test', 'integration_test'], join(root, 'flutter'))
run('flutter', ['test'], join(root, 'flutter'))
run('flutter', ['build', 'apk', '--debug', '--no-pub',
  '--dart-define=API_BASE_URL=http://10.0.2.2:28080'], join(root, 'flutter'))
if (includeIosSimulator) {
  run('flutter', ['build', 'ios', '--simulator', '--no-codesign', '--no-pub',
    '--dart-define=API_BASE_URL=http://127.0.0.1:28080'], join(root, 'flutter'))
}
requireCleanSource()
await access(join(root, 'web/dist/index.html'))
for (const service of services) await access(join(root, `backend/${service}/target/${service}-0.1.0-SNAPSHOT.jar`))
await access(join(root, 'flutter/build/app/outputs/flutter-apk/app-debug.apk'))
if (includeIosSimulator) await access(join(root, 'flutter/build/ios/iphonesimulator/Runner.app'))

await mkdir(output)
await cp(join(root, 'web/dist'), join(output, 'web'), { recursive: true })
await mkdir(join(output, 'backend'))
for (const service of services) {
  await cp(join(root, `backend/${service}/target/${service}-0.1.0-SNAPSHOT.jar`), join(output, 'backend', `${service}.jar`))
}
await mkdir(join(output, 'flutter', 'android'), { recursive: true })
await cp(join(root, 'flutter/build/app/outputs/flutter-apk/app-debug.apk'),
  join(output, 'flutter', 'android', 'uno-emulator-debug.apk'))
if (includeIosSimulator) {
  await mkdir(join(output, 'flutter', 'ios-simulator'), { recursive: true })
  await cp(join(root, 'flutter/build/ios/iphonesimulator/Runner.app'),
    join(output, 'flutter', 'ios-simulator', 'Runner.app'), { recursive: true })
}
await mkdir(join(output, 'deploy'))
for (const file of ['README.md', 'compose.yaml', 'compose.auth-local.yaml', 'compose.voice-local.yaml',
  'compose.images-local.yaml', 'livekit.yaml', 'nginx.conf', '.env.example',
  'backend.Dockerfile', 'web.Dockerfile', 'package-backend.Dockerfile', 'package-web.Dockerfile']) {
  await cp(join(root, 'deploy', file), join(output, 'deploy', file))
}
await cp(join(root, 'deploy', 'postgres'), join(output, 'deploy', 'postgres'), { recursive: true })
await mkdir(join(output, 'tools'))
await cp(join(root, 'tools', 'init-local-env.mjs'), join(output, 'tools', 'init-local-env.mjs'))
const manifest = {
  label,
  createdAt: new Date().toISOString(),
  stage: 'local-preview-not-production',
  sourceCommit: revision,
  sourceDirty: false,
  validation: ['npm --prefix web test', 'npm --prefix web run build',
    'mvn -f backend/pom.xml -Pdatabase-it clean verify',
    'dart analyze lib test integration_test (flutter)', 'flutter test',
    'flutter build apk --debug --no-pub --dart-define=API_BASE_URL=http://10.0.2.2:28080',
    ...(includeIosSimulator ? [
      'flutter build ios --simulator --no-codesign --no-pub --dart-define=API_BASE_URL=http://127.0.0.1:28080',
    ] : [])],
  included: ['web-static', 'backend-jars', 'android-emulator-debug-apk',
    ...(includeIosSimulator ? ['ios-simulator-app'] : []), 'local-compose-config-and-env-generator'],
  excluded: ['flutter-ipa', 'release-signed-mobile-builds', 'docker-images', 'secrets'],
  notes: `Local preview only. The Android debug APK targets an emulator using the Compose gateway at 10.0.2.2:28080; it is not a phone or production installer.${includeIosSimulator ? ' The Runner.app has only a simulator ad-hoc signature and targets the localhost gateway; it is not an IPA or an iPhone installer.' : ''} Local Compose can use the separate verified image archive with --no-build. Source-based Dockerfiles require the original repository. Browser/device end-to-end, signed mobile builds, and production deployment are separate gates.`,
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
