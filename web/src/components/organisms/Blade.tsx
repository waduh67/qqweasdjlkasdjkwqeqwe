import type { ReactNode } from 'react'
import {
  OverlayDrawer,
  DrawerHeader,
  DrawerHeaderTitle,
  DrawerBody,
  Button,
} from '@fluentui/react-components'
import { X } from 'lucide-react'
import { useConfirm } from '@/system'
import { ResourceForm } from './ResourceForm'
import type { CreationFlow } from './CreationReview'

/**
 * Resource creation uses a retained Basics/review layer. Other details remain
 * in a side drawer; both surfaces protect drafts marked dirty by their owner.
 */
export type BladeSize = 'sm' | 'lg' | 'full'

const FLUENT_SIZE: Record<BladeSize, 'medium' | 'large' | 'full'> = {
  sm: 'medium',
  lg: 'large',
  full: 'full',
}

export function Blade({
  open,
  title,
  subtitle,
  size = 'sm',
  dirty = false,
  onClose,
  footer,
  children,
  className,
  creation,
  layout,
}: {
  open: boolean
  title: ReactNode
  subtitle?: ReactNode
  size?: BladeSize
  /** Bila true, ESC/klik-luar/tombol tutup meminta konfirmasi sebelum menutup. */
  dirty?: boolean
  onClose: () => void
  footer?: ReactNode
  children: ReactNode
  /** Kelas tambahan pada drawer — mis. `blade-half` untuk lebar ~50% di desktop. */
  className?: string
  creation?: CreationFlow
  layout?: 'resource'
}) {
  const confirm = useConfirm()
  const requestClose = () => {
    if (!dirty) {
      onClose()
      return
    }
    // Form kotor → konfirmasi in-app (bukan `window.confirm`) sebelum membuang perubahan.
    void confirm({
      title: 'Tutup panel?',
      message: 'Perubahan belum disimpan akan hilang. Tutup panel?',
      confirmLabel: 'Tutup tanpa simpan',
      danger: true,
    }).then((ok) => {
      if (ok) onClose()
    })
  }

  if (creation || layout === 'resource') {
    if (!open) return null
    const reviewing = creation?.reviewing
    const prepare = () => creation?.prepare()
    return <ResourceForm title={title} className={className} onClose={onClose} dirty={dirty}
      readOnly={!creation} editing={creation?.editing} busy={creation?.busy} onReview={prepare} reviewAction={!!creation}
      onBack={() => creation?.back()} review={reviewing ? creation.summary : undefined}
      footer={footer}
      reviewFooter={reviewing ? <><Button disabled={creation.busy} onClick={creation.back}>Sebelumnya</Button>{footer}</> : undefined}>
      {children}
    </ResourceForm>
  }

  return (
    <OverlayDrawer
      open={open}
      position="end"
      size={FLUENT_SIZE[size]}
      modalType={footer ? 'modal' : 'non-modal'}
      className={`azure-blade${className ? ` ${className}` : ''}`}
      // Fluent memicu ini untuk ESC (non-modal: tak ada klik-scrim); kita saring
      // lewat requestClose (form kotor → konfirmasi). Karena `open` terkendali,
      // panel tetap terbuka selama `onClose` tak dipanggil.
      onOpenChange={(_, data) => {
        if (!data.open) requestClose()
      }}
    >
      <DrawerHeader>
        <DrawerHeaderTitle
          action={
            <Button
              appearance="subtle"
              aria-label="Tutup"
              icon={<X size={18} />}
              onClick={requestClose}
            />
          }
        >
          {title}
        </DrawerHeaderTitle>
        {subtitle && <p className="azure-blade-sub">{subtitle}</p>}
      </DrawerHeader>

      <DrawerBody className="azure-blade-body">{children}</DrawerBody>

      {footer && <div className="azure-blade-foot">{footer}</div>}
    </OverlayDrawer>
  )
}
