import { useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { Dialog, DialogActions, DialogBody, DialogContent, DialogSurface, DialogTitle } from '@fluentui/react-components'
import { Button } from '@/components/atoms'
import { IconClose } from '@/components/atoms/icons'

/** Shared Fluent dialog: use the same focus manager as drawers and menus. */
export function Modal({ title, onClose, children, footer, wide, layout = 'dialog', className = '' }: {
  title: ReactNode
  onClose: () => void
  children: ReactNode
  footer?: ReactNode
  wide?: boolean
  layout?: 'dialog' | 'resource'
  className?: string
}) {
  // Imperative dialogs have no DialogTrigger for Fluent to restore. Capture the
  // launcher before the surface moves focus; restore before disposal and after commit.
  const [launcher] = useState(() => document.activeElement instanceof HTMLElement ? document.activeElement : null)
  const surface = useRef<HTMLDivElement>(null)
  useLayoutEffect(() => () => {
    const closingSurface = surface.current
    const restore = () => {
      // Deactivate the closing modal before Fluent disposes its focus manager.
      // Deferring this until passive cleanup can leave the page aria-hidden.
      const anotherDialogHasFocus = () => {
        const focused = document.activeElement
        const dialog = focused instanceof HTMLElement ? focused.closest('[role="dialog"]') : null
        return dialog?.isConnected && dialog !== closingSurface && !(launcher && dialog.contains(launcher))
      }
      const focus = (target: HTMLElement | null) => {
        if (!target?.isConnected || target.matches(':disabled') || target.closest('[hidden], [inert]')) return false
        const previousTabIndex = target.getAttribute('tabindex')
        if (target.tabIndex < 0) target.setAttribute('tabindex', '-1')
        target.focus({ preventScroll: true })
        if (previousTabIndex === null) target.removeAttribute('tabindex')
        else target.setAttribute('tabindex', previousTabIndex)
        return document.activeElement === target
      }
      if (anotherDialogHasFocus()) return
      if (launcher !== document.body && focus(launcher)) return
      // A surviving parent dialog may have selected a new control while saving.
      const current = document.activeElement
      if (current instanceof HTMLElement) {
        const dialog = current.closest('[role="dialog"]')
        if (dialog?.isConnected && dialog !== closingSurface) return
      }
      const page = document.querySelector<HTMLElement>('main, #root') ?? [...document.body.children].find(
        (node): node is HTMLElement => node instanceof HTMLElement && !node.matches('script, style, link, [data-tabster-dummy], [hidden], [inert], [data-portal-node]') && !node.contains(closingSurface),
      ) ?? null
      focus(page)
    }
    restore()
    queueMicrotask(() => { if (!closingSurface?.isConnected) restore() })
  }, [launcher])
  return (
    <Dialog open onOpenChange={(_, data) => { if (!data.open) onClose() }}>
      <DialogSurface ref={surface} className={`console-dialog ${className}${wide ? ' console-dialog-wide' : ''}${layout === 'resource' ? ' resource-form-dialog' : ''}`}>
        <DialogBody>
          <div className="console-dialog-heading">
            <DialogTitle>{title}</DialogTitle>
            <Button variant="subtle" icon={<IconClose size={18} />} onClick={onClose} aria-label="Tutup" />
          </div>
          <DialogContent>{children}</DialogContent>
          {footer && <DialogActions>{footer}</DialogActions>}
        </DialogBody>
      </DialogSurface>
    </Dialog>
  )
}
