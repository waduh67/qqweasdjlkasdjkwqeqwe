import { useEffect, useState } from 'react'
import { Outlet, useOutletContext } from 'react-router-dom'
import { usePortalAuth } from './PortalAuthContext'
import {
  getPortalBilling,
  getPortalConnection,
  getPortalProfile,
  type PortalAccount,
  type PortalBilling,
  type PortalConnection,
} from './portalApi'
import { useAppShellNav } from '@/hooks/useAppShellNav'
import { BrandMark, Button, ThemeToggle } from '@/components/atoms'
import { SidebarNav, type NavGroup } from '@/components/molecules'
import {
  IconChat,
  IconDashboard,
  IconLogout,
  IconReceipt,
  IconSidebar,
  IconUsers,
  IconWifi,
} from '@/components/atoms/icons'

/**
 * Menu portal: satu grup tanpa label (tak ada yang perlu diciutkan pada lima menu) dan
 * tanpa gerbang izin — pelanggan selalu boleh melihat rekeningnya sendiri; yang membatasi
 * adalah token portal di server, yang hanya pernah menjawab tentang dirinya.
 */
const PORTAL_NAV: NavGroup[] = [
  {
    label: null,
    items: [
      { to: '/portal', label: 'Ringkasan', permission: null, icon: IconDashboard, end: true },
      { to: '/portal/tagihan', label: 'Tagihan', permission: null, icon: IconReceipt },
      { to: '/portal/koneksi', label: 'Koneksi', permission: null, icon: IconWifi },
      { to: '/portal/bantuan', label: 'Bantuan', permission: null, icon: IconChat },
      { to: '/portal/profil', label: 'Profil', permission: null, icon: IconUsers },
    ],
  },
]

/** Data rekening yang dipakai lintas halaman portal, dibagikan lewat konteks `<Outlet>`. */
export interface PortalData {
  profile: PortalAccount | null
  billing: PortalBilling | null
  connection: PortalConnection | null
  /** Pemuatan awal sudah selesai (berhasil MAUPUN gagal) — lihat catatan di bawah. */
  ready: boolean
  tenantSlug: string
  customerName: string
  reloadBilling: () => Promise<unknown>
}

export function usePortalData() {
  return useOutletContext<PortalData>()
}

/**
 * Kerangka PORTAL pelanggan — bentuknya SAMA dengan konsol operator: bar aksen membentang
 * penuh di puncak, sidebar gelap di kiri (menciut jadi rel ikon di layar lebar, jadi laci di
 * ponsel), konten di kanan. Yang berbeda cuma isinya: lima menu, tanpa gerbang izin.
 *
 * Kelas & perilakunya diambil dari shell yang sudah ada (`.app`, `.topbar`, `.sidebar`,
 * [SidebarNav], [useAppShellNav]) — bukan disalin — supaya portal tak pernah menyimpang
 * sendiri saat shell konsol dirapikan.
 *
 * Data rekening ditarik SEKALI di sini lalu dibagikan ke halaman: pelanggan berpindah menu
 * beberapa kali dalam satu kunjungan, dan menembak ulang tiga permintaan tiap kali pindah
 * membuat portal terasa berat di jaringan ponsel.
 */
export function PortalLayout() {
  const { customer, logout } = usePortalAuth()
  const { collapsed, navOpen, toggleNav, closeNav, shellClass } = useAppShellNav('ftth.portal.sidebarCollapsed')

  const [profile, setProfile] = useState<PortalAccount | null>(null)
  const [billing, setBilling] = useState<PortalBilling | null>(null)
  const [connection, setConnection] = useState<PortalConnection | null>(null)
  // Dibedakan dari "data masih null": permintaan yang GAGAL juga berakhir null, dan layar
  // yang menulis "Memuat…" selamanya lebih membingungkan ketimbang mengaku tak dapat data.
  const [ready, setReady] = useState(false)

  const reloadBilling = () => getPortalBilling().then(setBilling).catch(() => setBilling(null))

  useEffect(() => {
    void Promise.allSettled([
      getPortalProfile().then(setProfile).catch(() => setProfile(null)),
      reloadBilling(),
      getPortalConnection().then(setConnection).catch(() => setConnection(null)),
    ]).then(() => setReady(true))
  }, [])

  const initials = (customer?.name ?? '?')
    .split(' ')
    .slice(0, 2)
    .map((s) => s[0]?.toUpperCase())
    .join('')

  const context: PortalData = {
    profile,
    billing,
    connection,
    ready,
    tenantSlug: customer?.tenantSlug ?? '',
    customerName: customer?.name ?? 'Pelanggan',
    reloadBilling,
  }

  return (
    <div className={shellClass}>
      <header className="topbar">
        <div className="row" style={{ gap: '0.5rem' }}>
          <Button
            variant="subtle"
            icon={<IconSidebar size={18} />}
            onClick={toggleNav}
            aria-label={collapsed ? 'Lebarkan sidebar' : 'Ciutkan sidebar'}
            title={collapsed ? 'Lebarkan sidebar' : 'Ciutkan sidebar'}
            aria-expanded={navOpen}
          />
          {/* Chip ISP: pelanggan bisa berlangganan di lebih dari satu tempat, dan portalnya
              satu pintu — jadi "sedang melihat punya siapa" harus selalu terbaca. */}
          <span className="badge accent">{customer?.tenantSlug}</span>
        </div>
        <div className="row" style={{ gap: '0.75rem' }}>
          <ThemeToggle />
          <span className="user-chip">
            <span className="avatar" aria-hidden>{initials}</span>
            <div className="portal-user-name" style={{ lineHeight: 1.2 }}>
              <div style={{ fontWeight: 600, fontSize: '0.85rem' }}>{customer?.name}</div>
              <div className="muted tnum" style={{ fontSize: '0.75rem' }}>{customer?.code}</div>
            </div>
          </span>
          <Button
            variant="subtle"
            icon={<IconLogout size={18} />}
            onClick={() => void logout()}
            aria-label="Keluar"
            title="Keluar"
          />
        </div>
      </header>

      {navOpen && <button type="button" className="nav-scrim" aria-label="Tutup menu" onClick={closeNav} />}

      <aside className="sidebar">
        <div className="brand">
          <span className="logo" aria-hidden>
            <BrandMark size={22} />
          </span>
          <span className="brand-text">Portal</span>
        </div>

        <SidebarNav groups={PORTAL_NAV} can={() => true} storageKey="ftth.navGroups.portal" />
      </aside>

      <div className="main">
        <main className="content portal-page">
          <Outlet context={context} />
        </main>
      </div>
    </div>
  )
}
