import { cp, mkdir, readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const assets = join(root, 'assets')
const webAssets = join(root, 'web/public/game-assets')
const mobileAssets = join(root, 'flutter/assets/cards')
await mkdir(webAssets, { recursive: true })
await mkdir(mobileAssets, { recursive: true })
await cp(join(assets, 'ready'), webAssets, { recursive: true })
await cp(join(assets, 'ready/cards'), mobileAssets, { recursive: true })
const notices = [
  'Card artwork: VerzatileDev, 4 Colour Cards, CC0. https://verzatiledev.itch.io/4colour',
  'UI, icons and audio: Kenney, CC0. https://kenney.nl',
  'Not affiliated with or endorsed by Mattel. UNO is not an original project brand.',
  '',
  await readFile(join(assets, 'vendor/4colour-cards/LICENSE-SOURCE.md'), 'utf8'),
  await readFile(join(assets, 'vendor/casino-audio/License.txt'), 'utf8'),
  await readFile(join(assets, 'vendor/ui-pack/License.txt'), 'utf8'),
  await readFile(join(assets, 'vendor/board-game-icons/License.txt'), 'utf8'),
].join('\n\n')
await writeFile(join(webAssets, 'THIRD_PARTY_NOTICES.txt'), notices)
await writeFile(join(mobileAssets, 'THIRD_PARTY_NOTICES.txt'), notices)
console.log('Synced selected local assets to web and Flutter, including license notices.')
