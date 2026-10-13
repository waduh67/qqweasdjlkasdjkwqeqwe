import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '@/api/client'
import type { SiteView } from '@/api/network'
import type { PageResponse } from '@/api/types'

export const mapSiteKey = (scope: string) => `ftth.map.site:${scope}`
function readSite(scope: string | null): string {
  try { return scope ? localStorage.getItem(mapSiteKey(scope)) ?? '' : '' } catch { return '' }
}
function storeSite(scope: string | null, id: string) {
  if (!scope) return
  try {
    if (id) localStorage.setItem(mapSiteKey(scope), id)
    else localStorage.removeItem(mapSiteKey(scope))
  } catch { /* Selection still works when browser storage is unavailable. */ }
}

/** Persist only the ID; restore coordinates from the current authorized site list. */
export function useMapSites(scope: string | null, enabled: boolean, onFocus: (site: SiteView, restore: boolean) => void) {
  const [sites, setSites] = useState<SiteView[]>([])
  const [selected, setSelected] = useState<SiteView | null>(null)
  const [loading, setLoading] = useState(enabled), [error, setError] = useState(''), [attempt, setAttempt] = useState(0)
  const choice = useRef(0), focus = useRef(onFocus)
  useEffect(() => { focus.current = onFocus }, [onFocus])
  const hasSavedSite = enabled && !!readSite(scope)

  useEffect(() => {
    let active = true
    const version = ++choice.current, saved = enabled ? readSite(scope) : ''
    setSites([]); setSelected(null); setError(''); setLoading(enabled)
    if (!enabled || !scope) return
    void (async () => {
      try {
        const all: SiteView[] = []
        let page = 0, pages = 1
        while (page < pages) {
          const result = await api.get<PageResponse<SiteView>>(`/api/sites?page=${page}&size=200`)
          if (!active) return
          all.push(...result.content)
          pages = result.totalPages
          page++
        }
        const valid = all.filter(site => Number.isFinite(site.location?.longitude) && Number.isFinite(site.location?.latitude)
          && Math.abs(site.location.longitude) <= 180 && Math.abs(site.location.latitude) <= 90)
        setSites(valid)
        if (version !== choice.current) return
        const restored = valid.find(site => site.id === saved) ?? null
        setSelected(restored)
        if (restored) focus.current(restored, true)
        else if (saved) storeSite(scope, '')
      } catch {
        if (active) setError('Daftar site gagal dimuat.')
      } finally {
        if (active) setLoading(false)
      }
    })()
    return () => { active = false }
  }, [scope, enabled, attempt])

  const select = useCallback((site: SiteView | null) => {
    choice.current++
    setSelected(site)
    storeSite(scope, site?.id ?? '')
    if (site) focus.current(site, false)
  }, [scope])
  return { sites, selected, loading, error, select, hasSavedSite, retry: () => setAttempt(value => value + 1) }
}
