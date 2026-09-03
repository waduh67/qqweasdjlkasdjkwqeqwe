import { useEffect, useRef } from 'react'
import { Text } from '@fluentui/react-components'
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '@/auth/useAuth'
import { useCan } from '@/auth/useCan'
import { useAppShellNav } from '@/hooks/useAppShellNav'
import { BrandMark, Button, ThemeToggle } from '@/components/atoms'
import { EnvSwitcher } from '@/components/molecules'
import { NotificationBell } from '@/components/organisms'
import { Breadcrumbs } from '@/components/molecules'
import { SidebarNav, type NavGroup } from '@/components/molecules'
import {
  IconAlert,
  IconArea,
  IconAudit,
  IconChart,
  IconChat,
  IconCustomers,
  IconDashboard,
  IconGauge,
  IconInbox,
  IconInventory,
  IconLogout,
  IconMap,
  IconMonitor,
  IconPackage,
  IconPlus,
  IconReceipt,
  IconRoute,
  IconShield,
  IconSidebar,
  IconUsers,
  IconWifi,
  IconWorkOrder,
} from '@/components/atoms/icons'
import { HOTSPOT_VIEW_PERMISSIONS } from '@/api/hotspot'
/**
 * Navigasi dikelompokkan menurut alur kerja (operasi jaringan vs administrasi),
 * bukan sekadar daftar datar — pada belasan menu, pengelompokan membuat operator
 * menemukan yang dicari tanpa memindai satu per satu. Tiap item tetap difilter
 * izin, cermin RBAC di server. Seksi berlabel bisa diciutkan (lihat [SidebarNav]).
 */
const GROUPS: NavGroup[] = [
  {
    label: null,
    items: [
      { to: '/', label: 'Dashboard', permission: null, icon: IconDashboard, end: true },
      { to: '/reports', label: 'Laporan', permission: 'reporting.report.view', icon: IconChart },
      { to: '/subscription', label: 'Langganan Aplikasi', permission: 'billing.subscription.view', icon: IconGauge },
    ],
  },
  {
    label: 'Jaringan',
    items: [
      { to: '/map', label: 'Peta Jaringan', permission: 'gis.map.view', icon: IconMap },
      { to: '/inventory', label: 'Inventory', permission: 'network.odp.view', icon: IconInventory },
      { to: '/bras', label: 'BRAS & RADIUS', permission: 'bng.nas.view', icon: IconGauge },
      { to: '/acs', label: 'ACS / TR-069', permission: 'cpe.acs.view', icon: IconWifi },
      { to: '/vpn', label: 'Akun VPN', permission: 'vpn.peer.view', icon: IconRoute },
      { to: '/monitoring', label: 'Monitoring', permission: 'monitoring.dashboard.view', icon: IconMonitor },
      { to: '/network-provisioning', label: 'Provisioning Jaringan', permission: 'provisioning.segment.view', icon: IconRoute },
      { to: '/provisioning', label: 'Provisioning', permission: 'monitoring.provisioning.view', icon: IconInbox },
    ],
  },
  {
    label: 'Layanan Pelanggan',
    items: [
      { to: '/express-psb', label: 'PSB Ekspres', permission: 'customer.customer.create', icon: IconPlus },
      // Impor PPPoE tak lagi menu tersendiri — pintu masuknya kini tombol di halaman Pelanggan
      // (menyatu dengan rencana impor/ekspor pelanggan via CSV). Rute /import-pppoe tetap ada.
      { to: '/customers', label: 'Pelanggan', permission: 'customer.customer.view', icon: IconCustomers },
      // Keluhan yang dilaporkan pelanggan sendiri dari portal — tetangga Pelanggan, bukan
      // Insiden: yang di sini lahir dari manusia, yang di Lapangan lahir dari alarm.
      { to: '/helpdesk', label: 'Meja Bantuan', permission: 'helpdesk.ticket.view', icon: IconChat },
      { to: '/invoices', label: 'Tagihan', permission: 'billing.invoice.view', icon: IconReceipt },
       { to: '/catalog', label: 'Paket Internet', permission: 'catalog.plan.view', icon: IconPackage },
       { to: '/hotspot', label: 'Hotspot & Voucher', permission: HOTSPOT_VIEW_PERMISSIONS, icon: IconWifi },

    ],
  },
  {
    label: 'Lapangan',
    items: [
      { to: '/incidents', label: 'Insiden', permission: 'incident.ticket.view', icon: IconAlert },
      // Papan dispatch (semua WO) di-gate izin dashboard = khusus operator; teknisi (yang
      // cuma punya `order.view`+`order.field`) tak melihatnya, hanya "Tugas Saya" di bawah.
      { to: '/work-orders', label: 'Work Order', permission: 'workorder.dashboard.view', icon: IconWorkOrder },
      { to: '/my-work-orders', label: 'Tugas Saya', permission: 'workorder.order.field', icon: IconInbox },
    ],
  },
  {
    label: 'Administrasi',
    items: [
      { to: '/users', label: 'Pengguna', permission: 'iam.user.view', icon: IconUsers },
      { to: '/roles', label: 'Role & Izin', permission: 'iam.role.view', icon: IconShield },
      { to: '/areas', label: 'Area', permission: 'iam.area.view', icon: IconArea },
      { to: '/audit', label: 'Jejak Audit', permission: 'audit.log.view', icon: IconAudit },
      { to: '/notifications', label: 'Notifikasi', permission: 'notification.settings.view', icon: IconAlert },
      { to: '/payment-gateway', label: 'Payment Gateway', permission: 'billing.gateway.view', icon: IconPackage },
      { to: '/tax-settings', label: 'Pajak & BHP/USO', permission: 'billing.tax.view', icon: IconReceipt },
    ],
  },
]

/**
 * Rute yang petanya (atau kanvas lain) mengisi penuh area konten — tanpa padding
 * & lebar maksimum, menempel di bawah header dan di samping sidebar.
 */
const FLUSH_ROUTES = new Set(['/map'])

export function Layout() {
  const { user, logout, readOnly, subscriptionLock } = useAuth()
  const { can, isPlatformAdmin } = useCan()
  const location = useLocation()
  const navigate = useNavigate()
  const { collapsed, navOpen, toggleNav, closeNav, shellClass } = useAppShellNav()

  const flush = FLUSH_ROUTES.has(location.pathname)

  /**
   * Dorong ke `/subscription` SEKALI saja saat kunci pertama kali terbaca. Sekali, bukan
   * terus-menerus: keputusannya adalah konsol jadi baca-saja, bukan disandera — setelah
   * melihat tagihannya, operator harus tetap bebas membuka data pelanggannya. Banner merah
   * di bawah yang menjaga agar alasannya tak hilang dari pandangan.
   */
  const redirected = useRef(false)
  useEffect(() => {
    if (!readOnly || redirected.current) return
    redirected.current = true
    if (location.pathname !== '/subscription') navigate('/subscription')
    // location.pathname sengaja dibaca tanpa jadi dependency: efek ini hanya boleh bereaksi
    // pada perubahan status kunci, bukan pada tiap perpindahan halaman.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [readOnly, navigate])

  const initials = (user?.name ?? '?')
    .split(' ')
    .slice(0, 2)
    .map((s) => s[0]?.toUpperCase())
    .join('')

  return (
    <div className={shellClass}>
      {/* Header Azure full-width: anak langsung .app (grid-area topbar) agar bar biru
          membentang di atas sidebar & konten, bukan hanya kolom kanan. */}
      <header className="topbar">
        <div className="row" style={{ gap: '0.5rem' }}>
          {/* Satu tombol, dua arti: menciutkan sidebar di layar lebar, membuka laci nav
              di ponsel (lihat useAppShellNav) — sesuai yang dilihat pengguna di layarnya. */}
          <Button
            variant="subtle"
            icon={<IconSidebar size={18} />}
            onClick={toggleNav}
            aria-label={collapsed ? 'Lebarkan sidebar' : 'Ciutkan sidebar'}
            title={collapsed ? 'Lebarkan sidebar' : 'Ciutkan sidebar'}
            aria-expanded={navOpen}
          />
          <Text as="span" className="badge accent" size={200} weight="semibold">{user?.tenantSlug}</Text>
          {user?.platformAdmin && <Text as="span" className="badge" size={200} weight="semibold">platform admin</Text>}
        </div>
        <div className="row" style={{ gap: '0.75rem' }}>
          {/* Lonceng sebelum kendali lain: inilah satu-satunya kontrol di header yang
              berubah sendiri tanpa disentuh, jadi ia yang paling sering dilirik. */}
          <NotificationBell />
          <ThemeToggle />
          {/* Chip pengguna = pintu ke keamanan akun. Titik oranye muncul selama 2FA
              belum dipasang — pengingat yang selalu terlihat tanpa memblokir kerja. */}
          <Link
            to="/account/security"
            className="user-chip"
            title="Keamanan akun"
            style={{ textDecoration: 'none', color: 'inherit' }}
          >
            <span className="avatar" aria-hidden>
              {initials}
            </span>
            <div>
              <Text as="span" block size={200} weight="semibold">{user?.name}</Text>
              <Text as="span" block className="muted" size={100}>{user?.email}</Text>
            </div>
            {user && !user.twoFactorEnabled && (
              <span
                aria-label="Verifikasi dua langkah belum aktif"
                title="Verifikasi dua langkah belum aktif"
                style={{
                  width: 8,
                  height: 8,
                  borderRadius: '50%',
                  background: 'var(--warning, #f0a30a)',
                  flexShrink: 0,
                }}
              />
            )}
          </Link>
          <Button
            variant="subtle"
            icon={<IconLogout size={18} />}
            onClick={() => void logout()}
            aria-label="Keluar"
            title="Keluar"
          />
        </div>
      </header>

      {/* Latar gelap di belakang laci nav ponsel: menutup laci saat disentuh di luar,
          sekaligus memberi tahu bahwa konten di belakang sedang tak aktif. */}
      {navOpen && <button type="button" className="nav-scrim" aria-label="Tutup menu" onClick={closeNav} />}

      <aside className="sidebar">
        <div className="brand">
          <span className="logo" aria-hidden>
            <BrandMark size={22} />
          </span>
          <span className="brand-text">NetOps</span>
        </div>

        {/* Platform admin sedang menengok area tenant — switcher konteks di puncak sidebar. */}
        {isPlatformAdmin && <EnvSwitcher current="tenant" />}

        <SidebarNav groups={GROUPS} can={can} storageKey="ftth.navGroups.tenant" />
      </aside>

      <div className="main">
        {/* Banner menetap, bukan toast: kuncinya berlaku sampai dibayar, jadi alasannya harus
            tetap terlihat di halaman mana pun operator berada — termasuk saat ia lupa. */}
        {readOnly && (
          <div
            role="alert"
            className="row"
            style={{
              justifyContent: 'space-between',
              gap: '0.75rem',
              flexWrap: 'wrap',
              padding: '0.6rem 1rem',
              background: 'color-mix(in srgb, var(--danger) 12%, var(--surface))',
              borderBottom: '1px solid color-mix(in srgb, var(--danger) 45%, transparent)',
              color: 'var(--danger)',
            }}
          >
            <div className="row" style={{ gap: '0.5rem' }}>
              <IconAlert size={16} />
              <Text as="span" size={200}>
                <Text as="strong" size={200} weight="semibold">Langganan aplikasi menunggak</Text>
                {subscriptionLock && subscriptionLock.daysOverdue > 0
                  ? ` ${subscriptionLock.daysOverdue} hari.`
                  : '.'}{' '}
                Konsol dalam mode baca-saja — data tetap terbaca, tapi perubahan ditolak sampai
                tagihan dilunasi.
              </Text>
            </div>
            <Link to="/subscription" style={{ textDecoration: 'none' }}>
              <Button variant="primary">Bayar sekarang</Button>
            </Link>
          </div>
        )}
        <main className={flush ? 'content content-flush' : 'content'}>
          {!flush && (
            <div className="breadcrumb-bar">
              <Breadcrumbs />
            </div>
          )}
          <Outlet />
        </main>
      </div>
    </div>
  )
}
