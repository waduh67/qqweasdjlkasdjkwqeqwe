import { useState } from 'react'
import { NavLink } from 'react-router-dom'
import { IconChevronsUpDown, IconCheck } from './icons'

/**
 * Switcher konteks Platform ↔ Tenant di puncak sidebar, bergaya pil "environment"
 * (mirip Sandbox/Production pada referensi). Hanya dipakai platform admin —
 * menggantikan menu "Tampilan Tenant/Platform" lama. Murni navigasi: tiap opsi
 * menautkan ke shell terkait; server tetap otoritatif atas izin.
 */
export function EnvSwitcher({ current }: { current: 'platform' | 'tenant' }) {
  const [open, setOpen] = useState(false)
  const label = current === 'platform' ? 'Platform' : 'Tenant'

  return (
    <div className="env-switch">
      <button
        type="button"
        className="env-switch-btn"
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="menu"
        aria-expanded={open}
        title={`Konteks: ${label}`}
      >
        <span className={`env-dot ${current}`} aria-hidden />
        <span className="env-label">{label}</span>
        <IconChevronsUpDown size={15} />
      </button>

      {open && (
        <>
          <div className="env-scrim" onClick={() => setOpen(false)} />
          <ul className="env-menu" role="menu">
            <li>
              <NavLink
                to="/platform"
                className={current === 'platform' ? 'current' : undefined}
                onClick={() => setOpen(false)}
              >
                <span className="env-dot platform" aria-hidden />
                Platform
                {current === 'platform' && <IconCheck size={15} className="env-check" />}
              </NavLink>
            </li>
            <li>
              <NavLink
                to="/"
                className={current === 'tenant' ? 'current' : undefined}
                onClick={() => setOpen(false)}
              >
                <span className="env-dot tenant" aria-hidden />
                Tenant
                {current === 'tenant' && <IconCheck size={15} className="env-check" />}
              </NavLink>
            </li>
          </ul>
        </>
      )}
    </div>
  )
}
