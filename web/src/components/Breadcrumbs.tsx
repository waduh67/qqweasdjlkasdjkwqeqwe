import { useNavigate, useLocation } from 'react-router-dom'
import {
  Breadcrumb,
  BreadcrumbButton,
  BreadcrumbDivider,
  BreadcrumbItem,
} from '@fluentui/react-components'

/**
 * Breadcrumb global ala Azure Portal — dipasang di atas area konten tiap halaman
 * untuk menunjukkan hierarki. Label diturunkan dari segmen path; segmen ber-`:id`
 * (angka/uuid) ditampilkan sebagai "Detail". Klik crumb non-terakhir → navigasi.
 */
const LABELS: Record<string, string> = {
  '': 'Beranda',
  reports: 'Laporan',
  subscription: 'Langganan Aplikasi',
  map: 'Peta Jaringan',
  inventory: 'Inventory',
  olts: 'OLT',
  bras: 'BRAS & RADIUS',
  vpn: 'Akun VPN',
  'vpn-servers': 'Server VPN',
  monitoring: 'Monitoring',
  provisioning: 'Provisioning',
  'express-psb': 'PSB Ekspres',
  customers: 'Pelanggan',
  'import-pppoe': 'Impor PPPoE',
  'import-customers': 'Impor Pelanggan',
  invoices: 'Tagihan',
  catalog: 'Paket Internet',
  incidents: 'Insiden',
  'work-orders': 'Work Order',
  'my-work-orders': 'Tugas Saya',
  users: 'Pengguna',
  roles: 'Role & Izin',
  areas: 'Area',
  audit: 'Jejak Audit',
  notifications: 'Notifikasi',
  'payment-gateway': 'Payment Gateway',
  'tax-settings': 'Pajak & BHP/USO',
  platform: 'Platform',
  tenants: 'Tenant',
  billing: 'Billing Langganan',
}

function labelFor(segment: string): string {
  if (LABELS[segment]) return LABELS[segment]
  // Segmen id (angka atau uuid) → "Detail".
  if (/^[0-9a-f-]{6,}$/i.test(segment) || /^\d+$/.test(segment)) return 'Detail'
  return segment.charAt(0).toUpperCase() + segment.slice(1)
}

export function Breadcrumbs() {
  const location = useLocation()
  const navigate = useNavigate()
  const segments = location.pathname.split('/').filter(Boolean)

  // Susun crumb: Beranda + tiap segmen (dengan path kumulatif).
  const crumbs: Array<{ label: string; path: string }> = [{ label: LABELS[''], path: '/' }]
  let acc = ''
  for (const seg of segments) {
    acc += `/${seg}`
    crumbs.push({ label: labelFor(seg), path: acc })
  }

  return (
    <Breadcrumb aria-label="Breadcrumb" size="small">
      {crumbs.map((c, i) => {
        const last = i === crumbs.length - 1
        return (
          <span key={c.path} style={{ display: 'contents' }}>
            <BreadcrumbItem>
              <BreadcrumbButton
                current={last}
                onClick={last ? undefined : () => navigate(c.path)}
              >
                {c.label}
              </BreadcrumbButton>
            </BreadcrumbItem>
            {!last && <BreadcrumbDivider />}
          </span>
        )
      })}
    </Breadcrumb>
  )
}
