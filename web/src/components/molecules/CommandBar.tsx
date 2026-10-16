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
  layout = 'responsive',
}: {
  primary?: CommandAction
  actions?: CommandAction[]
  /** Wrap all actions onto visible rows in panels with limited width. */
  layout?: 'responsive' | 'wrap'
}) {
  if (layout === 'wrap') return (
    <Toolbar className="azure-commandbar commandbar-wrap" aria-label="Aksi">
      {primary && <CommandButton action={primary} primary />}
      {actions.map(action => <CommandButton key={action.key} action={action} />)}
    </Toolbar>
  )
  const leading = primary
  const remaining = actions.filter(action => action !== leading)
  const visible = remaining.filter(action => action.pinned)
  const secondary = remaining.filter(action => !visible.includes(action))
  return (
    <Toolbar className="azure-commandbar" aria-label="Aksi">
      {leading && <CommandButton action={leading} primary />}
      {visible.map(action => <CommandActionItem key={action.key} action={action} />)}
      <div className="command-secondary">
        {leading && secondary.length > 0 && !secondary[0].dividerBefore && <CommandDivider />}
        {secondary.map((action) => <CommandActionItem key={action.key} action={action} />)}
      </div>
      {secondary.length > 0 && <div className="command-overflow"><Menu positioning="below-end"><MenuTrigger disableButtonEnhancement><MenuButton aria-label="Aksi lainnya" size="small" appearance="subtle" icon={<MoreHorizontal size={18} />}>Aksi lainnya</MenuButton></MenuTrigger><MenuPopover><MenuList>{secondary.map(action => <MenuItem key={action.key} icon={action.icon} disabled={action.disabled} onClick={action.onClick}>{action.label}</MenuItem>)}</MenuList></MenuPopover></Menu></div>}
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
