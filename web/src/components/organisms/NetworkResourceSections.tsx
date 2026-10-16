import { useState, type ReactNode } from 'react'
import { ResourceNavigation, type ResourceNavigationItem } from './ResourceNavigation'
import './network-resource.css'

export type NetworkResourceSection = ResourceNavigationItem<string> & { content: ReactNode }

/** Visit sections lazily, then retain their local selection and unsaved work. */
export function NetworkResourceSections({ label, sections }: { label: string; sections: NetworkResourceSection[] }) {
  const [selected, setSelected] = useState(sections[0]?.key)
  const [visited, setVisited] = useState(() => new Set([sections[0]?.key]))
  const active = sections.some(section => section.key === selected) ? selected : sections[0]?.key
  if (!active) return null
  return <ResourceNavigation label={label} items={sections} active={active} onChange={key => {
    setSelected(key); setVisited(previous => new Set([...previous, key]))
  }}>
    {sections.filter(section => section.key === active || visited.has(section.key)).map(section =>
      <div key={section.key} className="network-resource-section flat-sections" hidden={section.key !== active}>{section.content}</div>)}
  </ResourceNavigation>
}

export function NetworkProperties({ fields }: { fields: Array<{ label: string; value: ReactNode }> }) {
  return <dl className="network-properties">{fields.map(field => <div key={field.label}><dt>{field.label}</dt><dd>{field.value ?? '—'}</dd></div>)}</dl>
}
