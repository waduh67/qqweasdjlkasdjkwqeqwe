import { useEffect, useMemo, useState } from 'react'
import { Text } from '@fluentui/react-components'
import { CreditCard, Pause, Play, Trash2 } from 'lucide-react'
import { api, ApiError } from '../api/client'
import type { PageResponse } from '../api/types'
import { getPlatformBillingSettings } from '../api/platformBilling'
import { useCan } from '../auth/useCan'
import { Blade } from '@/components/organisms'
import { DataTable, type Column, type RowAction } from '@/components/organisms'
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
    { key: 'name', header: 'Nama', sortValue: (t) => t.name, cell: (t) => <Text as="strong" weight="semibold" >{t.name}</Text> },
    { key: 'slug', header: 'Slug', sortValue: (t) => t.slug, cell: (t) => <Text as="span" className="badge">{t.slug}</Text> },
    {
      key: 'status',
      header: 'Status',
      sortValue: (t) => t.status,
      cell: (t) => <StatusBadge status={t.status} />,
    },
  ]

  // Aksi per-baris di menu `…` ala Azure DataGrid (seragam dengan Pelanggan), bukan tombol inline.
  // Tenant `platform` tak punya aksi → menu `…` tak muncul (rowActions balik array kosong).
  const canSubscription = can('platform.subscription.view')
  const canManageTenant = can('platform.tenant.manage')
  const canDeleteTenant = can('platform.tenant.delete')
  const rowActions = (t: Tenant): RowAction[] => {
    if (t.slug === 'platform') return []
    const list: RowAction[] = []
    if (canSubscription)
      list.push({ key: 'subscription', label: 'Langganan', icon: <CreditCard size={16} />, onClick: () => setSubscription({ id: t.id, name: t.name }) })
    if (canManageTenant)
      list.push({
        key: 'toggle',
        label: t.status === 'ACTIVE' ? 'Suspend' : 'Aktifkan',
        icon: t.status === 'ACTIVE' ? <Pause size={16} /> : <Play size={16} />,
        onClick: () => void run(() => api.post(`/api/platform/tenants/${t.id}/${t.status === 'ACTIVE' ? 'suspend' : 'activate'}`)),
      })
    if (canDeleteTenant)
      list.push({ key: 'delete', label: 'Hapus', icon: <Trash2 size={16} />, onClick: () => setConfirmDelete(t) })
    return list
  }

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <PageHeader
        title="Tenant"
        actions={
          can('platform.tenant.create') && (
            <Button variant="primary" onClick={() => openDraft({ ...EMPTY })}>
              <IconPlus size={15} /> Onboarding tenant
            </Button>
          )
        }
      />

      {error && <Text as="p" className="error">{error}</Text>}
      {notice && <Text as="p" className="muted">{notice}</Text>}

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
        rowActions={canSubscription || canManageTenant || canDeleteTenant ? rowActions : undefined}
        empty={
          <EmptyState
            title={query || statusFilter ? 'Tidak ada tenant yang cocok' : 'Belum ada tenant'}
            icon={<IconBuilding size={32} />}
          />
        }
      />

      <Blade
        open={draft != null}
        title="Onboarding tenant baru"
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
            <Text as="p" style={{ margin: 0 }}>
              Hapus tenant <Text as="strong" weight="semibold" >{confirmDelete.name}</Text> (<code>{confirmDelete.slug}</code>) beserta
              <Text as="strong" weight="semibold" > SELURUH datanya</Text> secara permanen? Tindakan ini{' '}
              <Text as="strong" weight="semibold" >tidak bisa dibatalkan</Text>.
            </Text>
          }
        />
      )}
    </div>
  )
}
