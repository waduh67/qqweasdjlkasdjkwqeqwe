import type { ReactElement } from 'react'
import { Toolbar, ToolbarButton, ToolbarDivider, Button } from '@fluentui/react-components'

/**
 * CommandBar ala Azure Portal — bilah aksi di atas area tabel/konten.
 *
 * Aturan tata letak (mengikuti Azure): aksi **primary `+ Create` dipatok di paling
 * KIRI** sebagai tombol primary menonjol; aksi sekunder (Delete/Export/Refresh)
 * berjajar ke kanannya, tiap tombol berikon. Logika disabled diserahkan pemanggil
 * (mis. `Delete` nonaktif bila belum ada baris terpilih).
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
    <Toolbar aria-label="Aksi" className="azure-commandbar">
      {primary && (
        <Button
          appearance="primary"
          icon={primary.icon}
          onClick={primary.onClick}
          disabled={primary.disabled}
        >
          {primary.label}
        </Button>
      )}
      {primary && actions.length > 0 && <ToolbarDivider />}
      {actions.map((a) => (
        <span key={a.key} style={{ display: 'contents' }}>
          {a.dividerBefore && <ToolbarDivider />}
          <ToolbarButton icon={a.icon} onClick={a.onClick} disabled={a.disabled}>
            {a.label}
          </ToolbarButton>
        </span>
      ))}
    </Toolbar>
  )
}
