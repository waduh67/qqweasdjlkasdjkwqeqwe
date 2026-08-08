import type { ReactElement } from 'react'

/**
 * CommandBar ala Azure Portal — bilah aksi DATAR di atas area tabel/konten.
 *
 * Tiap aksi = tombol datar ikon+teks: ikon beraksen (biru Azure), teks netral
 * bobot normal, tanpa isian/kotak; hover memunculkan abu tipis — persis command
 * bar Azure. Dibangun dari tombol native (bukan Fluent `Toolbar`) agar gaya &
 * warna hover-nya SERAGAM dengan tombol lain di aplikasi yang sudah ala Azure,
 * alih-alih mengikuti token tema Fluent yang membuat hover memutih.
 *
 * Aturan tata letak (mengikuti Azure): aksi `primary` (mis. `+ Tambah`) dipatok
 * paling KIRI; aksi sekunder (Hapus/Segarkan/Ekspor) berjajar ke kanannya, tiap
 * tombol berikon. Logika disabled diserahkan pemanggil (mis. `Hapus` nonaktif bila
 * belum ada baris terpilih).
 */
export type CommandAction = {
  key: string
  label: string
  icon?: ReactElement
  onClick: () => void
  disabled?: boolean
  /** Sisipkan pemisah vertikal SEBELUM aksi ini. */
  dividerBefore?: boolean
}

export function CommandBar({
  primary,
  actions = [],
}: {
  primary?: CommandAction
  actions?: CommandAction[]
}) {
  return (
    <div className="azure-commandbar" role="toolbar" aria-label="Aksi">
      {primary && <CommandButton action={primary} primary />}
      {primary && actions.length > 0 && <span className="cmd-divider" aria-hidden />}
      {actions.map((a) => (
        <span key={a.key} style={{ display: 'contents' }}>
          {a.dividerBefore && <span className="cmd-divider" aria-hidden />}
          <CommandButton action={a} />
        </span>
      ))}
    </div>
  )
}

/**
 * Satu tombol perintah. Aksi sekunder = datar (ikon beraksen + label normal);
 * aksi `primary` = terisi biru menonjol sebagai CTA (mis. `+ Tambah`).
 */
function CommandButton({ action, primary }: { action: CommandAction; primary?: boolean }) {
  return (
    <button
      className={primary ? 'cmd-btn cmd-primary' : 'cmd-btn'}
      onClick={action.onClick}
      disabled={action.disabled}
    >
      {action.icon && (
        <span className="cmd-icon" aria-hidden>
          {action.icon}
        </span>
      )}
      {action.label}
    </button>
  )
}
