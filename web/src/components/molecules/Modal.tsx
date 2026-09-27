import type { ReactNode } from 'react'
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
