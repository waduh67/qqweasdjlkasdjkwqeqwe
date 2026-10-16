import { MoreHorizontal } from 'lucide-react'
import type { ReactElement } from 'react'
import { Menu, MenuButton, MenuItem, MenuList, MenuPopover, MenuTrigger, Toolbar, ToolbarDivider } from '@fluentui/react-components'
import { Button } from '@/components/atoms'

export type CommandAction = {
  key: string
  label: string
  icon?: ReactElement
  onClick: () => void
  disabled?: boolean
  /** Tetap tampil di toolbar, termasuk pada layar kecil. */
  pinned?: boolean
  /** Sisipkan pemisah vertikal SEBELUM aksi ini. */
  dividerBefore?: boolean
}

export function CommandBar({
  primary,
  actions = [],
  compact = false,
}: {
  primary?: CommandAction
  actions?: CommandAction[]
  /** Keep narrow map panels on one row, with remaining actions in the menu. */
  compact?: boolean
}) {
  const leading = primary ?? (compact ? actions.find(action => action.key !== 'delete') : undefined)
  const remaining = actions.filter(action => action !== leading)
  const visible = compact ? remaining.filter(action => action.key === 'cable').slice(0, 1) : remaining.filter(action => action.pinned)
  const secondary = remaining.filter(action => !visible.includes(action))
  return (
    <Toolbar className={`azure-commandbar${compact ? ' commandbar-compact' : ''}`} aria-label="Aksi">
      {leading && <CommandButton action={leading} primary />}
      {visible.map(action => <CommandActionItem key={action.key} action={action} />)}
      <div className="command-secondary">
        {leading && secondary.length > 0 && !secondary[0].dividerBefore && <CommandDivider />}
        {secondary.map((action) => <CommandActionItem key={action.key} action={action} />)}
      </div>
      {secondary.length > 0 && <div className="command-overflow"><Menu positioning="below-end"><MenuTrigger disableButtonEnhancement><MenuButton aria-label="Aksi lainnya" size="small" appearance="subtle" icon={<MoreHorizontal size={18} />}>{compact ? null : 'Aksi lainnya'}</MenuButton></MenuTrigger><MenuPopover><MenuList>{secondary.map(action => <MenuItem key={action.key} icon={action.icon} disabled={action.disabled} onClick={action.onClick}>{action.label}</MenuItem>)}</MenuList></MenuPopover></Menu></div>}
    </Toolbar>
  )
}

function CommandActionItem({ action }: { action: CommandAction }) {
  return (
    <>
      {action.dividerBefore && <CommandDivider />}
      <CommandButton action={action} />
    </>
  )
}

function CommandDivider() {
  return <ToolbarDivider className="cmd-divider" role="separator" aria-orientation="vertical" />
}

function CommandButton({ action, primary }: { action: CommandAction; primary?: boolean }) {
  return (
    <Button
      variant="subtle"
      className={primary ? 'cmd-btn cmd-primary' : 'cmd-btn'}
      icon={action.icon}
      onClick={action.onClick}
      disabled={action.disabled}
    >
      {action.label}
    </Button>
  )
}
