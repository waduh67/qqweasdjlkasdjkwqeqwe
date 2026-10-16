import { useId, type ComponentType, type KeyboardEvent, type ReactNode } from 'react'
import { SelectField } from '@/components/atoms'
import type { IconProps } from '@/components/atoms/icons'
import './resource-navigation.css'

export interface ResourceNavigationItem<Key extends string> { key: Key; label: string; group?: string; icon?: ComponentType<IconProps> }

export function ResourceNavigation<Key extends string>({ label, items, active, onChange, children }: {
  label: string; items: ResourceNavigationItem<Key>[]; active: Key; onChange: (key: Key) => void; children: ReactNode
}) {
  const id = useId(), selected = items.find(item => item.key === active) ?? items[0]
  function move(event: KeyboardEvent<HTMLDivElement>) {
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
    const buttons = [...event.currentTarget.querySelectorAll<HTMLButtonElement>('[role="tab"]')]
    const index = buttons.indexOf(event.target as HTMLButtonElement)
    if (index < 0) return
    event.preventDefault()
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1
      : (index + (event.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length
    buttons[next]?.focus(); buttons[next]?.click()
  }
  return <div className="resource-detail-layout">
    <div className="resource-detail-menu" role="tablist" aria-label={label} aria-orientation="vertical" onKeyDown={move}>
      {items.map((item, index) => <div key={item.key} role="presentation">
        {item.group && item.group !== items[index - 1]?.group && <div className="resource-menu-group">{item.group}</div>}
        <button type="button" role="tab" id={`${id}-tab-${item.key}`} aria-selected={selected.key === item.key}
          aria-controls={`${id}-panel-${item.key}`} tabIndex={selected.key === item.key ? 0 : -1} onClick={() => onChange(item.key)}>
          {item.icon && <item.icon className="resource-menu-icon" aria-hidden />}
          <span className="resource-menu-text">{item.label}</span>
        </button>
      </div>)}
    </div>
    <div className="resource-detail-picker"><SelectField label={label} value={selected.key} onChange={(_, data) => onChange(data.value as Key)}>
      {items.map(item => <option key={item.key} value={item.key}>{item.label}</option>)}
    </SelectField></div>
    <section className="resource-detail-panel" id={`${id}-panel-${selected.key}`} role="tabpanel" aria-label={selected.label} tabIndex={-1}>
      {children}
    </section>
  </div>
}
