import type { ReactNode } from 'react'
import { Button } from '@/components/atoms'
import { Modal } from './Modal'

/**
 * Dialog konfirmasi in-app di atas [Modal] — pengganti `window.confirm` bawaan browser
 * agar konsisten dengan konvensi UI. `danger` mewarnai tombol aksi merah; `busy`
 * mengunci tombol saat aksi berjalan (mencegah klik ganda & penutupan tak sengaja).
 */
export function ConfirmDialog({
  title,
  message,
  confirmLabel = 'Ya',
  cancelLabel = 'Batal',
  danger = false,
  busy = false,
  onConfirm,
  onClose,
}: {
  title: ReactNode
  message: ReactNode
  confirmLabel?: string
  cancelLabel?: string
  danger?: boolean
  busy?: boolean
  onConfirm: () => void
  onClose: () => void
}) {
  return (
    <Modal
      title={title}
      onClose={() => !busy && onClose()}
      footer={
        <>
          <Button variant="subtle" onClick={onClose} disabled={busy}>
            {cancelLabel}
          </Button>
          <Button variant={danger ? 'danger' : 'primary'} onClick={onConfirm} disabled={busy}>
            {busy ? 'Memproses…' : confirmLabel}
          </Button>
        </>
      }
    >
      <div className="stack" style={{ gap: '0.6rem' }}>
        {message}
      </div>
    </Modal>
  )
}
