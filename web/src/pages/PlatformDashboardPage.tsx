import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { listTenants, type Tenant } from '../api/platform'
import {
  getPlatformBillingSettings,
  type PlatformBillingSettingsView,
} from '../api/platformBilling'
import { useAuth } from '../auth/useAuth'
import { useCan } from '../auth/useCan'
import { StatusBadge } from '../components/ui'
import {
  IconBuilding,
  IconGauge,
  IconRoute,
  IconShield,
  IconUsers,
  type IconProps,
} from '../components/icons'
import type { ComponentType } from 'react'

/**
 * Dashboard SaaS untuk Platform admin — sengaja lean, dirakit dari endpoint yang
 * sudah ada (daftar tenant + setelan billing platform). Bukan dashboard operasional
 * tenant: fokusnya kesehatan portofolio tenant & konfigurasi langganan, bukan alarm
 * jaringan. Tiap kartu/pintasan tetap difilter izin (platform admin lolos via flag).
 */
export function PlatformDashboardPage() {
  const { user } = useAuth()
  const { can } = useCan()
  const [tenants, setTenants] = useState<Tenant[] | null>(null)
  const [billing, setBilling] = useState<PlatformBillingSettingsView | null>(null)

  useEffect(() => {
    if (can('platform.tenant.view')) {
      void listTenants(200)
        .then((page) => setTenants(page.content))
        .catch(() => setTenants([]))
    }
    if (can('platform.billing.view')) {
      void getPlatformBillingSettings()
        .then(setBilling)
        .catch(() => undefined)
    }
  }, [can])

  // Tenant `platform` sendiri bukan pelanggan SaaS — jangan ikut dihitung.
  const customers = (tenants ?? []).filter((t) => t.slug !== 'platform')
  const active = customers.filter((t) => t.status === 'ACTIVE').length
  const suspended = customers.filter((t) => t.status !== 'ACTIVE').length

  const hour = new Date().getHours()
  const greeting = hour < 11 ? 'Selamat pagi' : hour < 15 ? 'Selamat siang' : hour < 19 ? 'Selamat sore' : 'Selamat malam'

  const fee =
    billing != null ? `${billing.currency} ${billing.defaultMonthlyFee.toLocaleString('id-ID')}` : '—'

  return (
    <div className="stack" style={{ gap: '1.5rem' }}>
      <div>
        <h1 className="page-title">
          {greeting}, {user?.name?.split(' ')[0]}
        </h1>
        <p className="page-sub">Ringkasan platform SaaS — portofolio tenant &amp; langganan.</p>
      </div>

      <div className="stat-grid">
        {tenants != null && <Stat label="Total tenant" value={customers.length} />}
        {tenants != null && <Stat label="Tenant aktif" value={active} />}
        {tenants != null && (
          <Stat label="Tenant ditangguhkan" value={suspended} accent={suspended > 0 ? 'warn' : undefined} />
        )}
        {billing != null && <StatText label="Biaya bulanan default" value={fee} />}
      </div>

      <div className="row wrap" style={{ alignItems: 'stretch', gap: '1rem' }}>
        {can('platform.tenant.view') && (
          <div className="card pad-0 grow" style={{ minWidth: 320 }}>
            <div className="card-head">
              <h3>Tenant</h3>
              <Link to="/platform/tenants" style={{ fontSize: '0.85rem' }}>
                Kelola →
              </Link>
            </div>
            {tenants == null ? (
              <div className="card-body muted">Memuat…</div>
            ) : customers.length === 0 ? (
              <div className="card-body muted">Belum ada tenant. Onboarding tenant pertama untuk mulai.</div>
            ) : (
              <table>
                <tbody>
                  {customers.slice(0, 8).map((t) => (
                    <tr key={t.id}>
                      <td>
                        <div style={{ fontSize: '0.88rem', fontWeight: 600 }}>{t.name}</div>
                        <div className="muted" style={{ fontSize: '0.8rem' }}>
                          {t.slug}
                        </div>
                      </td>
                      <td style={{ textAlign: 'right', width: '1%' }}>
                        <StatusBadge status={t.status} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        )}

        <div className="card grow" style={{ minWidth: 260 }}>
          <h3 style={{ marginTop: 0 }}>Pintasan</h3>
          <div className="stack" style={{ gap: '0.5rem' }}>
            <QuickLink to="/platform/tenants" icon={IconBuilding} label="Kelola tenant" hint="Onboarding & status tenant" show={can('platform.tenant.view')} />
            <QuickLink to="/platform/billing" icon={IconGauge} label="Billing langganan" hint="Gateway & harga default" show={can('platform.billing.view')} />
            <QuickLink to="/platform/vpn-servers" icon={IconRoute} label="Server VPN" hint="Endpoint & kapasitas VPN" show={can('vpn.server.view')} />
            <QuickLink to="/platform/users" icon={IconUsers} label="Pengguna" hint="Akun platform admin" show={can('iam.user.view')} />
            <QuickLink to="/platform/roles" icon={IconShield} label="Role & izin" hint="RBAC platform" show={can('iam.role.view')} />
          </div>
        </div>
      </div>
    </div>
  )
}

function Stat({ label, value, accent }: { label: string; value: number; accent?: 'crit' | 'warn' }) {
  return (
    <div className={`stat ${accent === 'crit' ? 'crit-bar' : accent === 'warn' ? 'warn-bar' : 'accent-bar'}`}>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value.toLocaleString('id-ID')}</div>
    </div>
  )
}

function StatText({ label, value }: { label: string; value: string }) {
  return (
    <div className="stat accent-bar">
      <div className="stat-label">{label}</div>
      <div className="stat-value" style={{ fontSize: '1.35rem' }}>
        {value}
      </div>
    </div>
  )
}

function QuickLink({
  to,
  icon: Icon,
  label,
  hint,
  show,
}: {
  to: string
  icon: ComponentType<IconProps>
  label: string
  hint: string
  show: boolean
}) {
  if (!show) return null
  return (
    <Link
      to={to}
      className="row"
      style={{ gap: '0.7rem', padding: '0.55rem 0.6rem', borderRadius: 'var(--radius-sm)', color: 'var(--text)' }}
    >
      <span className="avatar" aria-hidden style={{ borderRadius: 8 }}>
        <Icon size={17} />
      </span>
      <span>
        <div style={{ fontWeight: 600, fontSize: '0.9rem' }}>{label}</div>
        <div className="muted" style={{ fontSize: '0.78rem' }}>
          {hint}
        </div>
      </span>
    </Link>
  )
}
