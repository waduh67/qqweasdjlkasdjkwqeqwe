import { useEffect, useId, useRef, useState, type ComponentType } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { ChevronDown12Regular, ChevronDoubleLeft16Regular, ChevronDoubleRight16Regular, Search12Regular } from '@fluentui/react-icons'
import { Input } from '@fluentui/react-components'
import { Button } from '@/components/atoms'
import type { IconProps } from '@/components/atoms/icons'
import type { RequestCount } from '@/hooks/useRequestActionCount'

export type NavItem = {
  to: string
  label: string
  permission: string | readonly string[] | null
  icon: ComponentType<IconProps>
  end?: boolean
  access?: 'read' | 'write'
  badge?: RequestCount | null
}
export type NavGroup = { label: string | null; items: NavItem[] }

function loadClosed(key: string, defaults: string[]): Set<string> {
  try {
    const saved: unknown = JSON.parse(localStorage.getItem(key) ?? JSON.stringify(defaults))
    return new Set(Array.isArray(saved) ? saved.filter((label): label is string => typeof label === 'string') : [])
  } catch { return new Set() }
}

/** Groups open independently; saved choices survive navigation and refresh. */
export function SidebarNav({ groups, can, hasPermission = can, storageKey, compact = false, onToggle, expanded = true }: {
  groups: NavGroup[]
  can: (permission: string) => boolean
  hasPermission?: (permission: string) => boolean
  storageKey: string
  compact?: boolean
  onToggle?: () => void
  expanded?: boolean
}) {
  const key = `${storageKey}.v3.closed`
  const { pathname, search: locationSearch } = useLocation()
  const id = useId()
  const matchesPath = (item: NavItem) => {
    const [path, queryString] = item.to.split('?')
    const pathMatches = item.end ? pathname === path : pathname === path || pathname.startsWith(path + '/')
    return pathMatches && (!queryString || [...new URLSearchParams(queryString)].every(([key, value]) => (new URLSearchParams(locationSearch).get(key) ?? (key === 'tab' ? 'skus' : '')) === value))
  }
  const activeGroup = groups.find(group => group.items.some(matchesPath))?.label
  const [closed, setClosed] = useState(() => loadClosed(key, compact ? groups.flatMap(group => group.label ? [group.label] : []).slice(1).filter(label => label !== activeGroup) : []))
  const [query, setQuery] = useState('')
  const route = pathname + locationSearch
  const previousPath = useRef(route)

  useEffect(() => { if (!expanded) setQuery('') }, [expanded])
  useEffect(() => {
    if (previousPath.current === route) return
    previousPath.current = route
    setQuery('')
    if (activeGroup) setClosed(previous => {
      if (!previous.has(activeGroup)) return previous
      const next = new Set(previous); next.delete(activeGroup)
      try { localStorage.setItem(key, JSON.stringify([...next])) } catch { /* Optional preference storage. */ }
      return next
    })
  }, [route, activeGroup, key])

  const toggle = (label: string) => setClosed(previous => {
    const next = new Set(previous)
    if (next.has(label)) next.delete(label)
    else next.add(label)
    try { localStorage.setItem(key, JSON.stringify([...next])) } catch { /* Storage can be unavailable. */ }
    return next
  })
  const search = query.trim().toLocaleLowerCase('id')
  const visibleGroups = groups.map(group => ({ ...group, items: group.items.filter(item => {
    const check = item.access === 'read' ? hasPermission : can
    const permitted = item.permission === null || (typeof item.permission === 'string' ? check(item.permission) : item.permission.some(check))
    return permitted && (!search || `${group.label ?? ''} ${item.label}`.toLocaleLowerCase('id').includes(search))
  }) })).filter(group => group.items.length)

  return (
    <>
      <div className="nav-toolbar">
        <div className="nav-search">
          <Input size="small" aria-label="Cari menu" placeholder="Cari menu" contentBefore={<Search12Regular aria-hidden />} value={query} onChange={(_, data) => setQuery(data.value)} />
        </div>
        {onToggle && <Button size="medium" variant="subtle" className="nav-collapse" onClick={onToggle}
          aria-label={expanded ? 'Ciutkan navigasi' : 'Lebarkan navigasi'} title={expanded ? 'Ciutkan navigasi' : 'Lebarkan navigasi'} aria-expanded={expanded}
          icon={expanded ? <ChevronDoubleLeft16Regular /> : <ChevronDoubleRight16Regular />} />}
      </div>
      {visibleGroups.length === 0 && <p className="nav-search-empty">Menu tidak ditemukan.</p>}
      {visibleGroups.map((group, index) => {
        const isClosed = !!group.label && closed.has(group.label) && !search
        const sectionId = `${id}-${index}`
        return (
          <div key={group.label ?? 'main'} className={`nav-group${group.label ? ' nav-group--labeled' : ''}${isClosed ? ' collapsed' : ''}`}>
            {group.label && (
              <Button size="medium" variant="subtle" className="nav-label nav-group-toggle" onClick={() => toggle(group.label!)} aria-expanded={!isClosed} aria-controls={sectionId}>
                <ChevronDown12Regular className="nav-group-chevron" aria-hidden />
                <span>{group.label}</span>
              </Button>
            )}
            <nav id={sectionId} aria-label={group.label ?? 'Menu utama'}>
              {group.items.map(item => (
                <Link key={item.to} to={item.to} className={matchesPath(item) ? 'active' : ''} aria-current={matchesPath(item) ? 'page' : undefined} title={item.label + (item.badge?.status === 'ready' ? ' · ' + item.badge.count + ' perlu tindakan' : item.badge?.status === 'error' ? ' · Jumlah request belum tersedia' : '')} aria-label={item.label}>
                  <item.icon className="nav-icon" aria-hidden />
                  <span className="nav-text">{item.label}</span>
                  {item.badge && <span className={'nav-badge ' + item.badge.status} aria-label={item.badge.status === 'ready' ? item.badge.count + ' request perlu tindakan' : item.badge.status === 'error' ? 'Jumlah request belum tersedia' : 'Memuat jumlah request'}>{item.badge.status === 'ready' ? item.badge.count : item.badge.status === 'error' ? '!' : '…'}</span>}
                </Link>
              ))}
            </nav>
          </div>
        )
      })}
    </>
  )
}
