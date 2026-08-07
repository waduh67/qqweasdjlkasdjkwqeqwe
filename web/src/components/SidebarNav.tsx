import { useState } from 'react'
import { NavLink } from 'react-router-dom'
import { ChevronDown } from 'lucide-react'
import type { ComponentType } from 'react'
import type { IconProps } from './icons'

/**
 * Navigasi sidebar berkelompok ala left-nav Azure Portal: tiap seksi berlabel
 * (mis. "Jaringan", "Layanan Pelanggan") jadi header yang bisa diciutkan lewat
 * chevron — persis pola Azure (Overview / Infrastructure ▾ / …). Seksi tanpa label
 * (grup teratas berisi Dashboard) selalu tampil. Status buka/tutup tiap seksi
 * disimpan di localStorage agar bertahan antar-kunjungan.
 *
 * Dipakai bersama oleh shell tenant ([Layout]) dan shell platform ([PlatformLayout])
 * supaya perilaku & gaya seragam. Filter izin tetap di pemanggil lewat `can`.
 */
export type NavItem = {
  to: string
  label: string
  permission: string | null
  icon: ComponentType<IconProps>
  end?: boolean
}

export type NavGroup = { label: string | null; items: NavItem[] }

// Kunci penyimpanan status ciut per-seksi; nilai = daftar label seksi yang DICIUTKAN.
// Menyimpan yang diciutkan (bukan yang terbuka) berarti seksi baru default terbuka.
function loadCollapsed(storageKey: string): Set<string> {
  try {
    const raw = localStorage.getItem(storageKey)
    return new Set(raw ? (JSON.parse(raw) as string[]) : [])
  } catch {
    return new Set()
  }
}

export function SidebarNav({
  groups,
  can,
  storageKey,
}: {
  groups: NavGroup[]
  can: (permission: string) => boolean
  /** Kunci localStorage unik per-shell agar status ciut tenant & platform terpisah. */
  storageKey: string
}) {
  const [collapsed, setCollapsed] = useState<Set<string>>(() => loadCollapsed(storageKey))

  const toggle = (label: string) =>
    setCollapsed((prev) => {
      const next = new Set(prev)
      if (next.has(label)) next.delete(label)
      else next.add(label)
      localStorage.setItem(storageKey, JSON.stringify([...next]))
      return next
    })

  return (
    <>
      {groups.map((group, i) => {
        const visible = group.items.filter((item) => item.permission === null || can(item.permission))
        if (visible.length === 0) return null

        // Grup tanpa label (Dashboard dkk) tak bisa diciutkan — selalu tampil.
        if (!group.label) {
          return (
            <div key={i} className="nav-group">
              <nav>
                {visible.map((item) => (
                  <NavLink key={item.to} to={item.to} end={item.end ?? false} title={item.label}>
                    <item.icon size={18} />
                    <span className="nav-text">{item.label}</span>
                  </NavLink>
                ))}
              </nav>
            </div>
          )
        }

        const isCollapsed = collapsed.has(group.label)
        return (
          <div key={group.label} className={`nav-group${isCollapsed ? ' collapsed' : ''}`}>
            <button
              type="button"
              className="nav-label nav-group-toggle"
              onClick={() => toggle(group.label as string)}
              aria-expanded={!isCollapsed}
            >
              <span>{group.label}</span>
              <ChevronDown size={14} className="nav-group-chevron" aria-hidden />
            </button>
            {!isCollapsed && (
              <nav>
                {visible.map((item) => (
                  <NavLink key={item.to} to={item.to} end={item.end ?? false} title={item.label}>
                    <item.icon size={18} />
                    <span className="nav-text">{item.label}</span>
                  </NavLink>
                ))}
              </nav>
            )}
          </div>
        )
      })}
    </>
  )
}
