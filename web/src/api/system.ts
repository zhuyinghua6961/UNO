export type Bootstrap = {
  service: string
  stage: 'scaffold'
  protocolVersion: number
  plannedModes: string[]
  features: Record<string, boolean>
}

export async function getBootstrap(): Promise<Bootstrap> {
  const response = await fetch('/api/system/bootstrap', { signal: AbortSignal.timeout(4000) })
  if (!response.ok) throw new Error(`Backend returned ${response.status}`)
  const payload = await response.json() as Bootstrap
  if (payload.service !== 'game-service' || payload.stage !== 'scaffold') {
    throw new Error('Unexpected bootstrap response')
  }
  return payload
}
