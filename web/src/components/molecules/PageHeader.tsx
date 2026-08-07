import type { ReactNode } from 'react'

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
        <h1 className="page-title">{title}</h1>
        {subtitle && <p className="page-sub">{subtitle}</p>}
      </div>
      {actions && <div className="page-header-actions">{actions}</div>}
    </div>
  )
}
