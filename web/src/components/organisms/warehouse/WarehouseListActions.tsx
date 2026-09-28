import { Plus, RefreshCw } from 'lucide-react'
import { CommandBar, type CommandAction } from '@/components/molecules/CommandBar'

export function WarehouseListActions({ onRefresh, create, actions = [] }: {
  onRefresh: () => void; create?: Omit<CommandAction, 'key'>; actions?: CommandAction[]
}) {
  return <CommandBar primary={create ? { key: 'create', icon: <Plus size={16} />, ...create } : undefined}
    actions={[{ key: 'refresh', label: 'Segarkan', icon: <RefreshCw size={16} />, onClick: onRefresh },
      ...actions]} />
}
