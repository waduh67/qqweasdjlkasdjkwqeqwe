import { ApiError } from './client'

const PUBLIC_PORTAL_CONTEXT_PATH = '/api/public/hotspot/portal-context'

export interface ResolvePublicPortalContextRequest {
  state: string
}

export interface PublicPortalContext {
  displayName: string
  logoUrl: string | null
  redirectUrl: string | null
  clientMac: string | null
  clientIp: string | null
}

export async function resolvePublicPortalContext(
  request: ResolvePublicPortalContextRequest,
): Promise<PublicPortalContext> {
  const response = await fetch(`${PUBLIC_PORTAL_CONTEXT_PATH}/resolve`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })

  if (!response.ok) {
    let detail = response.statusText
    try {
      const body = await response.json()
      detail = body.detail ?? body.message ?? detail
    } catch {}
    throw new ApiError(response.status, detail)
  }

  return response.json() as Promise<PublicPortalContext>
}
