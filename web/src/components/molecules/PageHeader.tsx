import type { ReactNode } from 'react'
import { Text, typographyStyles } from '@fluentui/react-components'

/**
 * Kepala halaman ala Azure: judul + subjudul di kiri, slot aksi (CommandBar) di
 * kanan. Menggantikan pola `<h1 class="page-title">` + `<p class="page-sub">` yang
 * di-hardcode tiap halaman, sekaligus menyediakan tempat baku untuk toolbar aksi.
 */
export function PageHeader({
  title,
  subtitle,
  actions,
}: {
  title: ReactNode
  subtitle?: ReactNode
  actions?: ReactNode
}) {
  return (
    <div className="page-header">
      <div className="page-header-text">
        <h1 className="page-title" style={typographyStyles.title1}>{title}</h1>
        {subtitle && <Text as="p" className="page-sub" size={300}>{subtitle}</Text>}
      </div>
      {actions && <div className="page-header-actions">{actions}</div>}
    </div>
  )
}
