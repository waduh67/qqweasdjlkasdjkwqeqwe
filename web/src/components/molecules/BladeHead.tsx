import { Text, typographyStyles } from '@fluentui/react-components'
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
          <h3 className="blade-title" style={typographyStyles.subtitle1}>{title}</h3>
          {subtitle && <Text as="span" className="blade-sub" size={200}>{subtitle}</Text>}
        </div>
        <Button variant="subtle" icon={<IconClose size={18} />} onClick={onClose} aria-label={closeLabel} />
      </div>
    </header>
  )
}
