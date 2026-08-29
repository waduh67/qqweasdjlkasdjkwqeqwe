import type { ReactElement } from 'react'
import { Toolbar, ToolbarDivider } from '@fluentui/react-components'
import { Button } from '@/components/atoms'

export type CommandAction = {
  key: string
  label: string
  icon?: ReactElement
  onClick: () => void
  disabled?: boolean
  /** Sisipkan pemisah vertikal SEBELUM aksi ini. */
  dividerBefore?: boolean
}

export function CommandBar({
  primary,
  actions = [],
}: {
  primary?: CommandAction
  actions?: CommandAction[]
}) {
  return (
    <Toolbar className="azure-commandbar" aria-label="Aksi">
      {primary && <CommandButton action={primary} primary />}
      {primary && actions.length > 0 && <ToolbarDivider className="cmd-divider" />}
      {actions.map((action) => (
        <CommandActionItem key={action.key} action={action} />
      ))}
    </Toolbar>
  )
}

function CommandActionItem({ action }: { action: CommandAction }) {
  return (
    <>
      {action.dividerBefore && <ToolbarDivider className="cmd-divider" />}
      <CommandButton action={action} />
    </>
  )
}

function CommandButton({ action, primary }: { action: CommandAction; primary?: boolean }) {
  return (
    <Button
      variant={primary ? 'primary' : 'subtle'}
      className={primary ? 'cmd-btn cmd-primary' : 'cmd-btn'}
      icon={action.icon}
      onClick={action.onClick}
      disabled={action.disabled}
    >
      {action.label}
    </Button>
  )
}
