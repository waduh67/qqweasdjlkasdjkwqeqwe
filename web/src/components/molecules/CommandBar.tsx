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
      {primary && actions.length > 0 && <CommandDivider />}
      {actions.map((action) => (
        <CommandActionItem key={action.key} action={action} />
      ))}
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
