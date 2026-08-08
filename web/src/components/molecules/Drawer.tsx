import { useEffect, type ReactNode } from 'react'
import { Button } from '@/components/atoms'
import { IconClose } from '@/components/atoms/icons'

/** Panel geser dari kanan untuk detail (tren ONU, dsb). */
export function Drawer({
  title,
  onClose,
  children,
}: {
  title: ReactNode
  onClose: () => void
  children: ReactNode
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <>
      <div className="scrim" onClick={onClose} />
      <aside className="drawer" role="dialog" aria-modal="true">
        <div className="drawer-head">
          <h3 style={{ margin: 0 }}>{title}</h3>
          <Button variant="subtle" icon={<IconClose size={18} />} onClick={onClose} aria-label="Tutup" />
        </div>
        <div className="drawer-body">{children}</div>
      </aside>
    </>
  )
}
