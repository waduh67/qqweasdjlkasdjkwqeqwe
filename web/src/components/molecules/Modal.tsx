import { useEffect, useRef, type ReactNode } from 'react'
import { Button } from '@/components/atoms'
import { IconClose } from '@/components/atoms/icons'

/**
 * Dialog terpusat untuk form singkat (buat/ubah) — beda peran dari [Drawer] yang
 * dipakai untuk panel detail geser-kanan. Esc & klik latar menutup; `wide` untuk
 * form dua kolom.
 */
export function Modal({
  title,
  onClose,
  children,
  footer,
  wide,
}: {
  title: ReactNode
  onClose: () => void
  children: ReactNode
  footer?: ReactNode
  wide?: boolean
}) {
  // Native `<dialog open>` (showModal) menaruh modal di **top layer** browser — di atas
  // SEMUA konten ber-z-index, termasuk Blade/OverlayDrawer Fluent yang juga top-layer.
  // Tanpa ini, dialog konfirmasi "Tutup panel?" muncul DI BELAKANG flyout. Karena dialog
  // ini dipromosikan ke top layer setelah drawer, ia otomatis menumpuk di atasnya. ESC
  // ditangani native lewat event `cancel`.
  const ref = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const dlg = ref.current
    if (dlg && !dlg.open) dlg.showModal()
    return () => {
      if (dlg?.open) dlg.close()
    }
  }, [])

  return (
    <dialog
      ref={ref}
      className="modal-host"
      onCancel={(e) => {
        e.preventDefault()
        onClose()
      }}
    >
      <div className="scrim" onClick={onClose} />
      <div className={`modal${wide ? ' modal-wide' : ''}`} role="dialog" aria-modal="true">
        <div className="modal-head">
          <h3>{title}</h3>
          <Button variant="subtle" icon={<IconClose size={18} />} onClick={onClose} aria-label="Tutup" />
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-foot">{footer}</div>}
      </div>
    </dialog>
  )
}
