import type { ReactNode } from 'react'
import { IconInbox } from './icons'

export function EmptyState({ title, hint, icon }: { title: string; hint?: string; icon?: ReactNode }) {
  return (
    <div className="empty">
      {icon ?? <IconInbox size={34} />}
      <strong style={{ color: 'var(--text-2)' }}>{title}</strong>
      {hint && <span style={{ fontSize: '0.85rem' }}>{hint}</span>}
    </div>
  )
}
