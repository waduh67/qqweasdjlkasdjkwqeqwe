import type { ReactNode } from 'react'
import { Text } from '@fluentui/react-components'
import { IconInbox } from './icons'

export function EmptyState({ title, hint, icon }: { title: string; hint?: string; icon?: ReactNode }) {
  return (
    <div className="empty">
      {icon ?? <IconInbox size={34} />}
      <Text as="strong" weight="semibold">{title}</Text>
      {hint && <Text as="span" size={200}>{hint}</Text>}
    </div>
  )
}
