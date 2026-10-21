import { useEffect, useState } from 'react'
import { tokenStore } from '@/api/client'
import { getRequestActionCount } from '@/api/warehouse/referenceRequests'
import { useAuth } from '@/auth/useAuth'
import { useCan } from '@/auth/useCan'

export type RequestCount = { status: 'loading' } | { status: 'ready'; count: number } | { status: 'error' }
export function useRequestActionCount(reference: boolean): RequestCount | null {
  const { user } = useAuth(), { can } = useCan()
  const enabled = reference && ['warehouse.request.review', 'warehouse.request.approve', 'warehouse.request.receive', 'warehouse.request.handover'].some(can)
  const scope = user?.tenantId + ':' + user?.id + ':' + user?.permissions.join(',')
  const sessionVersion = tokenStore.getSessionVersion()
  const [saved, setSaved] = useState<{ scope: string; sessionVersion: number; value: RequestCount } | null>(null)
  useEffect(() => {
    if (!enabled) return
    let active = true, pending = false, repeat = false
    const version = sessionVersion
    const refresh = async () => {
      if (document.visibilityState === 'hidden') return
      if (pending) { repeat = true; return }
      pending = true
      try {
        const count = await getRequestActionCount()
        if (active) setSaved({ scope, sessionVersion: version, value: tokenStore.getSessionVersion() === version ? { status: 'ready', count } : { status: 'loading' } })
      } catch {
        if (active) setSaved({ scope, sessionVersion: version, value: tokenStore.getSessionVersion() === version ? { status: 'error' } : { status: 'loading' } })
      } finally {
        pending = false
        if (active && repeat) { repeat = false; void refresh() }
      }
    }
    const changed = () => void refresh()
    void refresh()
    const timer = window.setInterval(changed, 60_000)
    document.addEventListener('visibilitychange', changed)
    window.addEventListener('warehouse:requests-changed', changed)
    return () => { active = false; window.clearInterval(timer); document.removeEventListener('visibilitychange', changed); window.removeEventListener('warehouse:requests-changed', changed) }
  }, [enabled, scope, sessionVersion])
  return enabled ? saved?.scope === scope && saved.sessionVersion === sessionVersion ? saved.value : { status: 'loading' } : null
}
