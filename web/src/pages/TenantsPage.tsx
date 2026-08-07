import { useEffect, useMemo, useState } from 'react'
import { api, ApiError } from '../api/client'
import type { PageResponse } from '../api/types'
import { getPlatformBillingSettings } from '../api/platformBilling'
import { useCan } from '../auth/useCan'
import { Blade } from '../components/Blade'
import { DataTable, type Column } from '../components/DataTable'
import { ConfirmDialog, EmptyState, SearchInput, StatusBadge, Toolbar } from '../components/ui'
import { IconBuilding, IconPlus } from '../components/icons'
import { TenantSubscriptionModal } from './TenantSubscriptionModal'

interface Tenant {
  id: string
  slug: string
  name: string
  status: string
}

const EMPTY = { slug: '', name: '', adminEmail: '', adminName: '', adminPassword: '', monthlyFee: '' }

const STATUS_OPTIONS: { value: string; label: string }[] = [
  { value: '', label: 'Semua status' },
  { value: 'ACTIVE', label: 'Aktif' },
  { value: 'SUSPENDED', label: 'Ditangguhkan' },
]

/** Halaman platform admin: daftar tenant + onboarding tenant baru beserta admin awalnya. */
export function TenantsPage() {
  const { can } = useCan()
  const [tenants, setTenants] = useState<Tenant[]>([])
  const [query, setQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [loading, setLoading] = useState(true)
  const [draft, setDraft] = useState<typeof EMPTY | null>(null)
  const [initialDraft, setInitialDraft] = useState<typeof EMPTY | null>(null)
  const [subscription, setSubscription] = useState<{ id: string; name: string } | null>(null)
  const [confirmDelete, setConfirmDelete] = useState<Tenant | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [defaultFee, setDefaultFee] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  async function reload() {
    try {
      const page = await api.get<PageResponse<Tenant>>('/api/platform/tenants?size=50')
      setTenants(page.content)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void reload().catch((err) => setError(err instanceof ApiError ? err.message : 'Gagal memuat tenant'))
    // Harga default global untuk ditampilkan sebagai acuan saat onboarding (best-effort).
    if (can('platform.billing.view')) {
      void getPlatformBillingSettings()
        .then((s) => setDefaultFee(s.defaultMonthlyFee))
        .catch(() => undefined)
    }
  }, [])

  async function run(action: () => Promise<unknown>) {
    setError(null)
    try {
      await action()
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Operasi gagal')
    }
  }

  // Buka/tutup Blade form dengan snapshot untuk deteksi perubahan (dirty).
  const openDraft = (d: typeof EMPTY) => {
    setDraft(d)
    setInitialDraft(d)
  }
  const closeDraft = () => {
    setDraft(null)
    setInitialDraft(null)
  }
  const dirty = draft != null && JSON.stringify(draft) !== JSON.stringify(initialDraft)

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return tenants.filter((t) => {
      if (statusFilter && t.status !== statusFilter) return false
      if (!q) return true
      return t.name.toLowerCase().includes(q) || t.slug.toLowerCase().includes(q)
    })
  }, [tenants, query, statusFilter])

  const columns: Column<Tenant>[] = [
    { key: 'name', header: 'Nama', sortValue: (t) => t.name, cell: (t) => <strong>{t.name}</strong> },
    { key: 'slug', header: 'Slug', sortValue: (t) => t.slug, cell: (t) => <span className="badge">{t.slug}</span> },
    {
      key: 'status',
      header: 'Status',
      sortValue: (t) => t.status,
      cell: (t) => <StatusBadge status={t.status} />,
    },
    {
      key: 'actions',
      header: '',
      align: 'right',
      width: '1%',
      cell: (t) =>
        t.slug !== 'platform' ? (
          <div className="row" style={{ justifyContent: 'flex-end', gap: '0.4rem' }}>
            {can('platform.subscription.view') && (
              <button onClick={() => setSubscription({ id: t.id, name: t.name })}>Langganan</button>
            )}
            {can('platform.tenant.manage') && (
              <button
                onClick={() =>
                  void run(() =>
                    api.post(`/api/platform/tenants/${t.id}/${t.status === 'ACTIVE' ? 'suspend' : 'activate'}`),
                  )
                }
              >
                {t.status === 'ACTIVE' ? 'Suspend' : 'Aktifkan'}
              </button>
            )}
            {can('platform.tenant.delete') && (
              <button className="danger" onClick={() => setConfirmDelete(t)}>
                Hapus
              </button>
            )}
          </div>
        ) : null,
    },
  ]

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <div className="spread">
        <div>
          <h1 className="page-title">Tenant</h1>
          <p className="page-sub">Onboarding ISP baru dan kelola status tenant di platform.</p>
        </div>
        {can('platform.tenant.create') && (
          <button className="primary" onClick={() => openDraft({ ...EMPTY })}>
            <IconPlus size={15} /> Onboarding tenant
          </button>
        )}
      </div>

      {error && <p className="error">{error}</p>}
      {notice && <p className="muted">{notice}</p>}

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari nama atau slug…" />
        <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
          {STATUS_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(t) => t.id}
        loading={loading}
        initialSort={{ key: 'name', dir: 'asc' }}
        empty={
          <EmptyState
            title={query || statusFilter ? 'Tidak ada tenant yang cocok' : 'Belum ada tenant'}
            hint={query || statusFilter ? 'Coba ubah kata kunci atau filter.' : 'Onboarding tenant pertama untuk mulai.'}
            icon={<IconBuilding size={32} />}
          />
        }
      />

      <Blade
        open={draft != null}
        title="Onboarding tenant baru"
        subtitle='Membuat tenant, role "Tenant Admin" berisi seluruh izin non-platform, dan user admin pertamanya.'
        size="sm"
        dirty={dirty}
        onClose={closeDraft}
        footer={
          <>
            <button
              className="primary"
              onClick={() =>
                void run(async () => {
                  const { monthlyFee, ...rest } = draft!
                  await api.post('/api/platform/tenants', {
                    ...rest,
                    monthlyFee: monthlyFee.trim() === '' ? undefined : Number(monthlyFee),
                  })
                  setNotice(`Tenant "${draft!.slug}" siap. Admin bisa langsung masuk dengan tenant tersebut.`)
                  closeDraft()
                })
              }
            >
              Simpan
            </button>
            <button onClick={closeDraft}>Batal</button>
          </>
        }
      >
        {draft && (
          <div className="stack">
            <div className="row" style={{ alignItems: 'flex-start' }}>
              <label style={{ flex: 1 }}>
                <span>Slug</span>
                <input value={draft.slug} onChange={(e) => setDraft({ ...draft, slug: e.target.value })} placeholder="pt-fiber" />
              </label>
              <label style={{ flex: 2 }}>
                <span>Nama</span>
                <input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
              </label>
            </div>
            <div className="row" style={{ alignItems: 'flex-start' }}>
              <label style={{ flex: 1 }}>
                <span>Nama admin</span>
                <input value={draft.adminName} onChange={(e) => setDraft({ ...draft, adminName: e.target.value })} />
              </label>
              <label style={{ flex: 1 }}>
                <span>Email admin</span>
                <input
                  type="email"
                  value={draft.adminEmail}
                  onChange={(e) => setDraft({ ...draft, adminEmail: e.target.value })}
                />
              </label>
              <label style={{ flex: 1 }}>
                <span>Password admin</span>
                <input
                  type="password"
                  value={draft.adminPassword}
                  onChange={(e) => setDraft({ ...draft, adminPassword: e.target.value })}
                />
              </label>
            </div>
            <div className="row" style={{ alignItems: 'flex-start' }}>
              <label style={{ flex: 1 }}>
                <span>Harga bulanan khusus (Rp)</span>
                <input
                  type="number"
                  min={0}
                  step={1000}
                  value={draft.monthlyFee}
                  onChange={(e) => setDraft({ ...draft, monthlyFee: e.target.value })}
                  placeholder={
                    defaultFee != null ? `Default Rp ${defaultFee.toLocaleString('id-ID')}` : 'Kosongkan = harga default'
                  }
                />
                <span className="muted" style={{ fontSize: '0.8rem' }}>
                  Kosongkan untuk memakai harga default global. Isi untuk harga khusus tenant ini.
                </span>
              </label>
              <div style={{ flex: 1 }} />
            </div>
          </div>
        )}
      </Blade>

      {subscription && (
        <TenantSubscriptionModal
          tenantId={subscription.id}
          tenantName={subscription.name}
          onClose={() => setSubscription(null)}
        />
      )}

      {confirmDelete && (
        <ConfirmDialog
          title="Hapus tenant"
          danger
          busy={deleting}
          confirmLabel="Hapus permanen"
          onClose={() => setConfirmDelete(null)}
          onConfirm={async () => {
            setDeleting(true)
            await run(() => api.del(`/api/platform/tenants/${confirmDelete.id}`))
            setDeleting(false)
            setConfirmDelete(null)
          }}
          message={
            <p style={{ margin: 0 }}>
              Hapus tenant <strong>{confirmDelete.name}</strong> (<code>{confirmDelete.slug}</code>) beserta
              <strong> SELURUH datanya</strong> secara permanen? Tindakan ini{' '}
              <strong>tidak bisa dibatalkan</strong>.
            </p>
          }
        />
      )}
    </div>
  )
}
