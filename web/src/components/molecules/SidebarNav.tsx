import { useState } from 'react'
import { NavLink } from 'react-router-dom'
import { ChevronDown } from 'lucide-react'
import { Text } from '@fluentui/react-components'
import { Button } from '@/components/atoms'
import type { ComponentType } from 'react'
import type { IconProps } from '@/components/atoms/icons'

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

// Nilai tersimpan = daftar label seksi yang DIBUKA. Menyimpan yang terbuka (bukan yang
// diciutkan) berarti seksi default TERTUTUP ala left-nav Azure — user membuka seksi yang
// ia perlukan dan pilihannya bertahan antar-kunjungan.
function loadExpanded(storageKey: string): Set<string> {
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
  // Kunci dinaikkan ke `.v2` karena semantik berubah (dulu simpan yang diciutkan) —
  // data lama diabaikan agar seksi tetap default tertutup.
  const key = `${storageKey}.v2`
  const [expanded, setExpanded] = useState<Set<string>>(() => loadExpanded(key))

  const toggle = (label: string) =>
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(label)) next.delete(label)
      else next.add(label)
      localStorage.setItem(key, JSON.stringify([...next]))
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
                    <Text as="span" className="nav-text" size={200}>{item.label}</Text>
                  </NavLink>
                ))}
              </nav>
            </div>
          )
        }

        const isCollapsed = !expanded.has(group.label)
        return (
          <div key={group.label} className={`nav-group nav-group--labeled${isCollapsed ? ' collapsed' : ''}`}>
            <Button
              variant="subtle"
              className="nav-label nav-group-toggle"
              onClick={() => toggle(group.label as string)}
              aria-expanded={!isCollapsed}
            >
              {/* Chevron di KIRI label — pola pohon left-nav Azure (⌄ terbuka / › tertutup). */}
              <ChevronDown size={16} className="nav-group-chevron" aria-hidden />
              <Text as="span" size={100} weight="semibold">{group.label}</Text>
            </Button>
            {!isCollapsed && (
              <nav>
                {visible.map((item) => (
                  <NavLink key={item.to} to={item.to} end={item.end ?? false} title={item.label}>
                    <item.icon size={18} />
                    <Text as="span" className="nav-text" size={200}>{item.label}</Text>
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
