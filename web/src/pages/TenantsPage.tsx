import { useEffect, useMemo, useState } from 'react'
import { api, ApiError } from '../api/client'
import type { PageResponse } from '../api/types'
import { getPlatformBillingSettings } from '../api/platformBilling'
import { useCan } from '../auth/useCan'
import { Blade } from '@/components/organisms'
import { DataTable, type Column } from '@/components/organisms'
import { Button, EmptyState, SelectField, StatusBadge, TextField, Toolbar } from '@/components/atoms'
import { ConfirmDialog, SearchInput } from '@/components/molecules'
import { PageHeader } from '@/components/molecules'
import { IconBuilding, IconPlus } from '@/components/atoms/icons'
import { TenantSubscriptionModal } from '@/components/organisms/TenantSubscriptionModal'

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
              <Button onClick={() => setSubscription({ id: t.id, name: t.name })}>Langganan</Button>
            )}
            {can('platform.tenant.manage') && (
              <Button
                onClick={() =>
                  void run(() =>
                    api.post(`/api/platform/tenants/${t.id}/${t.status === 'ACTIVE' ? 'suspend' : 'activate'}`),
                  )
                }
              >
                {t.status === 'ACTIVE' ? 'Suspend' : 'Aktifkan'}
              </Button>
            )}
            {can('platform.tenant.delete') && (
              <Button variant="danger" onClick={() => setConfirmDelete(t)}>
                Hapus
              </Button>
            )}
          </div>
        ) : null,
    },
  ]

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <PageHeader
        title="Tenant"
        subtitle="Onboarding ISP baru dan kelola status tenant di platform."
        actions={
          can('platform.tenant.create') && (
            <Button variant="primary" onClick={() => openDraft({ ...EMPTY })}>
              <IconPlus size={15} /> Onboarding tenant
            </Button>
          )
        }
      />

      {error && <p className="error">{error}</p>}
      {notice && <p className="muted">{notice}</p>}

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari nama atau slug…" />
        <SelectField value={statusFilter} onChange={(_, data) => setStatusFilter(data.value)}>
          {STATUS_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </SelectField>
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
            <Button
              variant="primary"
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
            </Button>
            <Button onClick={closeDraft}>Batal</Button>
          </>
        }
      >
        {draft && (
          <div className="stack">
            <div className="row" style={{ alignItems: 'flex-start' }}>
              <div style={{ flex: 1 }}>
                <TextField label="Slug" value={draft.slug} onChange={(_, data) => setDraft({ ...draft, slug: data.value })} placeholder="pt-fiber" />
              </div>
              <div style={{ flex: 2 }}>
                <TextField label="Nama" value={draft.name} onChange={(_, data) => setDraft({ ...draft, name: data.value })} />
              </div>
            </div>
            <div className="row" style={{ alignItems: 'flex-start' }}>
              <div style={{ flex: 1 }}>
                <TextField label="Nama admin" value={draft.adminName} onChange={(_, data) => setDraft({ ...draft, adminName: data.value })} />
              </div>
              <div style={{ flex: 1 }}>
                <TextField
                  label="Email admin"
                  type="email"
                  value={draft.adminEmail}
                  onChange={(_, data) => setDraft({ ...draft, adminEmail: data.value })}
                />
              </div>
              <div style={{ flex: 1 }}>
                <TextField
                  label="Password admin"
                  type="password"
                  value={draft.adminPassword}
                  onChange={(_, data) => setDraft({ ...draft, adminPassword: data.value })}
                />
              </div>
            </div>
            <div className="row" style={{ alignItems: 'flex-start' }}>
              <div style={{ flex: 1 }}>
                <TextField
                  label="Harga bulanan khusus (Rp)"
                  type="number"
                  min={0}
                  step={1000}
                  value={draft.monthlyFee}
                  onChange={(_, data) => setDraft({ ...draft, monthlyFee: data.value })}
                  placeholder={
                    defaultFee != null ? `Default Rp ${defaultFee.toLocaleString('id-ID')}` : 'Kosongkan = harga default'
                  }
                  hint="Kosongkan untuk memakai harga default global. Isi untuk harga khusus tenant ini."
                />
              </div>
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
