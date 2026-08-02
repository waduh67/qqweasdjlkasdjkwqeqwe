import { useEffect, useMemo, useState } from 'react'
import { api, ApiError } from '../api/client'
import type { PageResponse } from '../api/types'
import { provisionXenditSubAccount } from '../api/payment'
import { useCan } from '../auth/useCan'
import { DataTable, type Column } from '../components/DataTable'
import { EmptyState, SearchInput, StatusBadge, Toolbar } from '../components/ui'
import { IconBuilding, IconPlus } from '../components/icons'

interface Tenant {
  id: string
  slug: string
  name: string
  status: string
}

const EMPTY = { slug: '', name: '', adminEmail: '', adminName: '', adminPassword: '' }

/** Draft form provisioning Xendit PLATFORM untuk satu tenant. */
interface ProvisionDraft {
  tenantId: string
  tenantName: string
  email: string
  businessName: string
}

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
  const [provision, setProvision] = useState<ProvisionDraft | null>(null)
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
            {can('billing.gateway.provision') && (
              <button
                onClick={() =>
                  setProvision({ tenantId: t.id, tenantName: t.name, email: '', businessName: t.name })
                }
              >
                Provisi Xendit
              </button>
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
          <button className="primary" onClick={() => setDraft({ ...EMPTY })}>
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

      {draft && (
        <div className="card stack">
          <h3 style={{ margin: 0 }}>Onboarding tenant baru</h3>
          <p className="muted">
            Membuat tenant, role &quot;Tenant Admin&quot; berisi seluruh izin non-platform, dan user admin pertamanya.
          </p>
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
          <div className="row">
            <button
              className="primary"
              onClick={() =>
                void run(async () => {
                  await api.post('/api/platform/tenants', draft)
                  setNotice(`Tenant "${draft.slug}" siap. Admin bisa langsung masuk dengan tenant tersebut.`)
                  setDraft(null)
                })
              }
            >
              Buat
            </button>
            <button onClick={() => setDraft(null)}>Batal</button>
          </div>
        </div>
      )}

      {provision && (
        <div className="card stack">
          <h3 style={{ margin: 0 }}>Provisikan Xendit (mode PLATFORM)</h3>
          <p className="muted">
            Membuat sub-account Xendit (xenPlatform) untuk <strong>{provision.tenantName}</strong> memakai akun master
            platform, lalu mengunci gateway tenant ke XENDIT/PLATFORM/aktif. Email harus unik di Xendit.
          </p>
          <div className="row" style={{ alignItems: 'flex-start' }}>
            <label style={{ flex: 1 }}>
              <span>Email sub-account</span>
              <input
                type="email"
                value={provision.email}
                onChange={(e) => setProvision({ ...provision, email: e.target.value })}
                placeholder="billing@pt-fiber.co.id"
              />
            </label>
            <label style={{ flex: 1 }}>
              <span>Nama bisnis</span>
              <input
                value={provision.businessName}
                onChange={(e) => setProvision({ ...provision, businessName: e.target.value })}
              />
            </label>
          </div>
          <div className="row">
            <button
              className="primary"
              disabled={!provision.email.trim()}
              onClick={() =>
                void run(async () => {
                  const result = await provisionXenditSubAccount(provision.tenantId, {
                    email: provision.email.trim(),
                    businessName: provision.businessName.trim() || null,
                  })
                  setNotice(
                    `Sub-account Xendit ${result.subAccountId} tersimpan untuk "${provision.tenantName}".` +
                      (result.callbackTokenSet ? '' : ' Token callback pakai fallback platform global.'),
                  )
                  setProvision(null)
                })
              }
            >
              Provisikan
            </button>
            <button onClick={() => setProvision(null)}>Batal</button>
          </div>
        </div>
      )}
    </div>
  )
}
