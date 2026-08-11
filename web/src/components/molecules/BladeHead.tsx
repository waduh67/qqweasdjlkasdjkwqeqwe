import { Button } from '@/components/atoms'
import { IconClose } from '@/components/atoms/icons'

/**
 * Kepala blade: judul (kode aset), baris jenis sumber daya, dan tombol tutup.
 * Judul dipotong elipsis, bukan dibungkus, agar tinggi kepala tetap dan command
 * bar di bawahnya tak naik-turun mengikuti panjang nama.
 */
export function BladeHead({
  title,
  subtitle,
  onClose,
  closeLabel = 'Tutup',
}: {
  title: string
  subtitle?: string
  onClose: () => void
  closeLabel?: string
}) {
  return (
    <header className="blade-head">
      <div className="spread">
        <div style={{ minWidth: 0 }}>
          <h3 className="blade-title">{title}</h3>
          {subtitle && <span className="blade-sub">{subtitle}</span>}
        </div>
        <Button variant="subtle" icon={<IconClose size={18} />} onClick={onClose} aria-label={closeLabel} />
      </div>
    </header>
  )
}
