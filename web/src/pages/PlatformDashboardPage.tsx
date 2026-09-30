import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ArrowRight, Building2, CreditCard, Mail, Network, Plus, RefreshCw } from 'lucide-react'
import { listTenants, type Tenant } from '../api/platform'
import { useCan } from '../auth/useCan'
import { Button, EmptyState, StatusBadge } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'

export function PlatformDashboardPage() {
  const { can } = useCan()
  const allowed = can('platform.tenant.view')
  const [tenants, setTenants] = useState<Tenant[] | null>(null)
  const [total, setTotal] = useState(0)
  const [error, setError] = useState(false)
  const [loading, setLoading] = useState(true)
  const reload = useCallback(async () => {
    if (!allowed) { setLoading(false); return }
    setLoading(true)
    setError(false)
    try {
      const page = await listTenants(200)
      setTenants(page.content)
      setTotal(page.totalElements)
    } catch { setError(true) }
    finally { setLoading(false) }
  }, [allowed])
  useEffect(() => { void reload() }, [reload])
  const customers = tenants?.filter(tenant => tenant.slug !== 'platform') ?? []
  const partial = !!tenants && total > tenants.length
  const actions = [
    { to: '/platform/billing', icon: CreditCard, title: 'Billing langganan', text: 'Atur harga dan penerimaan pembayaran tenant.', permission: 'platform.billing.view' },
    { to: '/platform/vpn-servers', icon: Network, title: 'Server VPN', text: 'Kelola hub dan koneksi router tenant.', permission: 'vpn.server.view' },
    { to: '/platform/radius-servers', icon: Network, title: 'Server RADIUS', text: 'Kelola cluster dan kapasitas RADIUS.', permission: 'radius.server.view' },
    { to: '/platform/email', icon: Mail, title: 'Setelan email', text: 'Atur pengirim dan pesan untuk pelanggan.', permission: 'platform.email.view' },
  ].filter(action => can(action.permission))
  return <div className="stack platform-overview">
    <PageHeader title="Dashboard platform" subtitle="Ringkasan tenant dan pengelolaan layanan NetOps." actions={can('platform.tenant.create') && <Button as="a" href="/platform/tenants?onboard=1" variant="primary" icon={<Plus size={18} />}>Tambah tenant</Button>} />
    {allowed && <>
      {error ? <div className="card load-error" role="alert"><div><strong>Data tenant gagal dimuat</strong><p>Ringkasan belum dapat ditampilkan. Coba muat kembali.</p></div><Button onClick={() => void reload()} icon={<RefreshCw size={16} />}>Coba lagi</Button></div> : loading ? <div className="card" role="status">Memuat ringkasan tenant…</div> : customers.length === 0 ?
        <section className="card onboarding-empty">
          <div className="empty-symbol"><Building2 size={30} strokeWidth={1.5} aria-hidden /></div>
          <div><span className="eyebrow">Mulai kelola layanan</span><h2>Belum ada tenant pelanggan</h2><p>Tambahkan organisasi pelanggan untuk mulai mengelola jaringan, layanan, dan gudangnya.</p>
            {can('platform.tenant.create') && <Link className="text-action" to="/platform/tenants?onboard=1">Tambah tenant <ArrowRight size={16} aria-hidden /></Link>}
          </div>
        </section> : <>
        <div className="overview-metrics" aria-label="Ringkasan tenant">
          <Metric label={partial ? 'Tenant dalam halaman ini' : 'Tenant pelanggan'} value={customers.length} hint={partial ? `${total} entri tersedia di daftar platform` : 'Tidak termasuk akun platform'} />
          <Metric label="Aktif" value={customers.filter(t => t.status === 'ACTIVE').length} hint={partial ? 'Dalam halaman ini' : 'Tenant dengan layanan aktif'} />
          <Metric label="Ditangguhkan" value={customers.filter(t => t.status === 'SUSPENDED').length} hint={partial ? 'Dalam halaman ini' : 'Tenant berstatus suspend'} />
        </div>
        <section className="card"><div className="section-heading"><div><h2>Tenant pelanggan</h2><p>Organisasi yang dikelola melalui platform.</p></div><Link to="/platform/tenants" className="text-action">Lihat semua <ArrowRight size={16} aria-hidden /></Link></div>
          <div className="tenant-overview-list">{customers.slice(0, 6).map(tenant => <div key={tenant.id}><span className="entity-symbol"><Building2 size={20} aria-hidden /></span><div><strong>{tenant.name}</strong><p>{tenant.slug}</p></div><StatusBadge status={tenant.status} /></div>)}</div>
        </section>
      </>}
    </>}
    {actions.length > 0 && <section><div className="section-heading"><div><h2>Pengelolaan platform</h2><p>Konfigurasi layanan bersama untuk seluruh tenant.</p></div></div><div className="action-grid">{actions.map(({ to, icon: Icon, title, text }) => <Link key={to} className="card action-card" to={to}><Icon size={22} strokeWidth={1.75} aria-hidden /><h3>{title}</h3><p>{text}</p><span className="text-action">Kelola <ArrowRight size={16} aria-hidden /></span></Link>)}</div></section>}
    {!allowed && actions.length === 0 && <EmptyState title="Selamat datang di NetOps" hint="Gunakan menu untuk membuka layanan yang tersedia bagi akun Anda." />}
  </div>
}
function Metric({ label, value, hint }: { label: string; value: number; hint: string }) {
  return <div className="card overview-metric"><span>{label}</span><strong>{value.toLocaleString('id-ID')}</strong><p>{hint}</p></div>
}
