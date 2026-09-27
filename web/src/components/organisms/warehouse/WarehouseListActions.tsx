import { Plus, RefreshCw, FilterX } from 'lucide-react'
import { CommandBar, type CommandAction } from '@/components/molecules/CommandBar'

export function WarehouseListActions({ onRefresh, onReset, create, actions = [] }: {
  onRefresh: () => void; onReset?: () => void; create?: Omit<CommandAction, 'key'>; actions?: CommandAction[]
}) {
  return <CommandBar primary={create ? { key: 'create', icon: <Plus size={16} />, ...create } : undefined}
    actions={[{ key: 'refresh', label: 'Segarkan', icon: <RefreshCw size={16} />, onClick: onRefresh },
      ...(onReset ? [{ key: 'reset', label: 'Hapus filter', icon: <FilterX size={16} />, onClick: onReset }] : []), ...actions]} />
}
