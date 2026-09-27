import { useEffect, useState, type ReactNode } from 'react'
import { Dialog, DialogActions, DialogBody, DialogContent, DialogSurface, DialogTitle } from '@fluentui/react-components'
import { Button } from '@/components/atoms'
import { IconClose } from '@/components/atoms/icons'

/** Shared Fluent dialog: use the same focus manager as drawers and menus. */
export function Modal({ title, onClose, children, footer, wide }: {
  title: ReactNode
  onClose: () => void
  children: ReactNode
  footer?: ReactNode
  wide?: boolean
}) {
  // Imperative dialogs have no DialogTrigger for Fluent to restore. Capture the
  // launcher before the surface moves focus, and restore only after it unmounts.
  const [launcher] = useState(() => document.activeElement instanceof HTMLElement ? document.activeElement : null)
  useEffect(() => () => {
    queueMicrotask(() => {
      const target = launcher?.isConnected && launcher !== document.body
        ? launcher
        : document.querySelector<HTMLElement>('main, #root') ?? document.body.firstElementChild as HTMLElement | null
      if (!target?.isConnected) return
      const focused = document.activeElement
      const activeDialog = focused instanceof HTMLElement ? focused.closest('[role="dialog"]') : null
      if (activeDialog && !activeDialog.contains(target)) return
      const previousTabIndex = target.getAttribute('tabindex')
      if (target.tabIndex < 0) target.setAttribute('tabindex', '-1')
      target.focus({ preventScroll: true })
      if (previousTabIndex === null) target.removeAttribute('tabindex')
      else target.setAttribute('tabindex', previousTabIndex)
    })
  }, [launcher])
  return (
    <Dialog open onOpenChange={(_, data) => { if (!data.open) onClose() }}>
      <DialogSurface className={`console-dialog${wide ? ' console-dialog-wide' : ''}`}>
        <DialogBody>
          <DialogTitle action={<Button variant="subtle" icon={<IconClose size={18} />} onClick={onClose} aria-label="Tutup" />}>{title}</DialogTitle>
          <DialogContent>{children}</DialogContent>
          {footer && <DialogActions>{footer}</DialogActions>}
        </DialogBody>
      </DialogSurface>
    </Dialog>
  )
}
