import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { IconChevronsUpDown, IconCheck } from './icons'

/**
 * Switcher konteks Platform ↔ Tenant di puncak sidebar — bergaya pemilih direktori/
 * langganan Azure Portal (kotak berbingkai: titik status + nama konteks + chevron
 * naik-turun, membuka dropdown pilihan). Hanya dipakai platform admin, menggantikan
 * menu "Tampilan Tenant/Platform" lama.
 *
 * Perpindahan lewat `useNavigate` eksplisit (bukan `<NavLink>`) supaya andal: memilih
 * opsi selalu menavigasi ke shell terkait lalu menutup menu — tak bergantung pada
 * pencocokan rute aktif. Server tetap otoritatif atas izin.
 */
const OPTIONS: { key: 'platform' | 'tenant'; to: string; name: string; desc: string }[] = [
  { key: 'platform', to: '/platform', name: 'Platform', desc: 'Konsol super-admin SaaS' },
  { key: 'tenant', to: '/', name: 'Tenant', desc: 'Operasi ISP sehari-hari' },
]

export function EnvSwitcher({ current }: { current: 'platform' | 'tenant' }) {
  const [open, setOpen] = useState(false)
  const navigate = useNavigate()
  const active = OPTIONS.find((o) => o.key === current) ?? OPTIONS[0]

  const choose = (to: string, key: 'platform' | 'tenant') => {
    setOpen(false)
    // Sudah di konteks ini → tak perlu navigasi (hindari reload rute yang sama).
    if (key !== current) navigate(to)
  }

  return (
    <div className="env-switch">
      <button
        type="button"
        className="env-switch-btn"
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="menu"
        aria-expanded={open}
        title={`Konteks: ${active.name}`}
      >
        <span className={`env-dot ${current}`} aria-hidden />
        <span className="env-switch-info">
          <span className="env-switch-name">{active.name}</span>
          <span className="env-switch-cap">Ganti konteks</span>
        </span>
        <IconChevronsUpDown size={15} />
      </button>

      {open && (
        <>
          <div className="env-scrim" onClick={() => setOpen(false)} />
          <ul className="env-menu" role="menu">
            {OPTIONS.map((o) => (
              <li key={o.key}>
                <button
                  type="button"
                  role="menuitemradio"
                  aria-checked={o.key === current}
                  className={o.key === current ? 'current' : undefined}
                  onClick={() => choose(o.to, o.key)}
                >
                  <span className={`env-dot ${o.key}`} aria-hidden />
                  <span className="env-switch-info">
                    <span className="env-switch-name">{o.name}</span>
                    <span className="env-switch-cap">{o.desc}</span>
                  </span>
                  {o.key === current && <IconCheck size={15} className="env-check" />}
                </button>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  )
}
