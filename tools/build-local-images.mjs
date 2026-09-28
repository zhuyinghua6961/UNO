import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { createReadStream, createWriteStream } from 'node:fs'
import { access, copyFile, cp, mkdir, mkdtemp, readFile, rename, rm, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { pipeline } from 'node:stream/promises'
import { fileURLToPath } from 'node:url'
import { createGzip } from 'node:zlib'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const label = process.argv[2]
if (process.argv.length !== 3 || !label || !/^[a-zA-Z0-9][a-zA-Z0-9._-]*$/.test(label) || label.includes('..')) {
  throw new Error('Usage: node tools/build-local-images.mjs <existing-package-label>')
}

const packageDir = join(root, 'release', label)
const packageArchive = join(root, 'release', `${label}.tar.gz`)
const output = join(root, 'release', `${label}-images`)
async function requireAbsent(path) {
  try { await access(path); throw new Error(`Output already exists: ${path}`) }
  catch (error) { if (error.code !== 'ENOENT') throw error }
}
function command(program, args, options = {}) {
  const result = spawnSync(program, args, { cwd: root, encoding: 'utf8', maxBuffer: 4 * 1024 * 1024, ...options })
  if (result.status !== 0) throw new Error(`${program} failed: ${(result.stderr || result.stdout || '').trim()}`)
  return result.stdout?.trim() ?? ''
}
async function sha256(path) {
  const hash = createHash('sha256')
  for await (const chunk of createReadStream(path)) hash.update(chunk)
  return hash.digest('hex')
}

await requireAbsent(output)
const builderCommit = command('git', ['rev-parse', 'HEAD'])
function requireCleanBuilder() {
  if (command('git', ['rev-parse', 'HEAD']) !== builderCommit || command('git', ['status', '--porcelain'])) {
    throw new Error('Commit image builder and Dockerfile changes before building images')
  }
}
requireCleanBuilder()
const packageSha = await sha256(packageArchive)
const expectedSha = (await readFile(`${packageArchive}.sha256`, 'utf8')).trim().split(/\s+/)[0]
if (expectedSha !== packageSha) throw new Error('Package archive checksum mismatch')
for (const file of ['manifest.json', 'SHA256SUMS']) {
  const archived = command('tar', ['-xOzf', packageArchive, `${label}/${file}`])
  const unpacked = (await readFile(join(packageDir, file), 'utf8')).trim()
  if (archived !== unpacked) throw new Error(`${file} differs from the checked package archive`)
}
command('shasum', ['-a', '256', '-c', 'SHA256SUMS'], { cwd: packageDir })
const packageManifest = JSON.parse(await readFile(join(packageDir, 'manifest.json'), 'utf8'))
if (packageManifest.label !== label || packageManifest.sourceDirty !== false ||
    !/^[0-9a-f]{40}$/.test(packageManifest.sourceCommit)) {
  throw new Error('Package manifest has no clean, valid source commit')
}
for (const service of ['gateway', 'identity-service', 'game-service']) {
  await access(join(packageDir, 'backend', `${service}.jar`))
}
await access(join(packageDir, 'web', 'index.html'))

const tag = `${label}-${packageManifest.sourceCommit.slice(0, 12)}`
const services = ['gateway', 'identity-service', 'game-service', 'web']
const stage = await mkdtemp(join(root, 'release', `.build-images-${label}-`))
try {
  const context = join(stage, 'context')
  await mkdir(join(context, 'backend'), { recursive: true })
  for (const service of services.filter(name => name !== 'web')) {
    await copyFile(join(packageDir, 'backend', `${service}.jar`), join(context, 'backend', `${service}.jar`))
  }
  await cp(join(packageDir, 'web'), join(context, 'web'), { recursive: true })
  await copyFile(join(packageDir, 'deploy', 'nginx.conf'), join(context, 'nginx.conf'))

  const images = []
  for (const service of services) {
    const name = `uno-local/${service}:${tag}`
    const dockerfile = join(root, 'deploy', service === 'web' ? 'package-web.Dockerfile' : 'package-backend.Dockerfile')
    const baseImage = (await readFile(dockerfile, 'utf8')).match(/^FROM (\S+)/m)?.[1]
    if (!baseImage?.includes('@sha256:')) throw new Error(`${dockerfile} must pin its base image digest`)
    const args = ['build', '--pull=false', '-f', dockerfile, '-t', name,
      '--label', `org.opencontainers.image.revision=${packageManifest.sourceCommit}`,
      '--label', `org.opencontainers.image.version=${label}`,
      '--label', `uno.package.sha256=${packageSha}`]
    if (service !== 'web') args.push('--build-arg', `SERVICE=${service}`)
    args.push(context)
    command('docker', args, { stdio: 'inherit' })
    const inspect = JSON.parse(command('docker', ['image', 'inspect', name]))[0]
    if (inspect.Config.Labels['org.opencontainers.image.revision'] !== packageManifest.sourceCommit ||
        inspect.Config.Labels['uno.package.sha256'] !== packageSha) {
      throw new Error(`Image ${name} has unexpected source labels`)
    }
    images.push({ service, name, imageId: inspect.Id, baseImage,
      dockerfileSha256: await sha256(dockerfile), platform: `${inspect.Os}/${inspect.Architecture}` })
  }

  const rawArchive = join(stage, 'images.tar')
  command('docker', ['save', '-o', rawArchive, ...images.map(image => image.name)], { stdio: 'inherit' })
  const compressedArchive = join(stage, 'images.tar.gz')
  await pipeline(createReadStream(rawArchive), createGzip({ level: 9 }), createWriteStream(compressedArchive))
  await rm(rawArchive)
  await rm(context, { recursive: true })
  const manifest = {
    label,
    stage: 'local-preview-not-production',
    createdAt: new Date().toISOString(),
    builderCommit,
    sourceCommit: packageManifest.sourceCommit,
    packageArchiveSha256: packageSha,
    tag,
    images,
    notes: 'Images are built from the verified package JARs and Web files with pinned base image digests. Image IDs identify this local build, and the archive checksum identifies the saved bytes. This does not establish a registry digest or production readiness.',
  }
  requireCleanBuilder()
  await writeFile(join(stage, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n')
  await writeFile(join(stage, 'SHA256SUMS'), `${await sha256(compressedArchive)}  images.tar.gz\n${await sha256(join(stage, 'manifest.json'))}  manifest.json\n`)
  await rename(stage, output)
  console.log(`Created ${output} from package ${label}; tag ${tag}`)
} finally {
  await rm(stage, { recursive: true, force: true })
}
