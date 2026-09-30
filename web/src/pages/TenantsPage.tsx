import { CreationSummary, useCreationReview } from '@/components/organisms/CreationReview'
import { useSearchParams } from 'react-router-dom'
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
import { FormSection, PageHeader } from '@/components/molecules'
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
  const [searchParams, setSearchParams] = useSearchParams()
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
  const [saving, setSaving] = useState(false)
  const creation = useCreationReview(draft != null)
  const [formError, setFormError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  async function reload() {
    setLoading(true)
    setError(null)
    try {
      const page = await api.get<PageResponse<Tenant>>('/api/platform/tenants?size=50')
      setTenants(page.content)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Gagal memuat daftar tenant. Coba segarkan kembali.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void reload()
    // Harga default global untuk ditampilkan sebagai acuan saat onboarding (best-effort).
    if (can('platform.billing.view')) {
      void getPlatformBillingSettings()
        .then((s) => setDefaultFee(s.defaultMonthlyFee))
        .catch(() => undefined)
    }
  }, [can])

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
    setFormError(null)
    setDraft(d)
    setInitialDraft(d)
  }
  const closeDraft = () => {
    setDraft(null)
    setInitialDraft(null)
  }
  useEffect(() => {
    if (searchParams.get('onboard') === '1' && can('platform.tenant.create')) {
      openDraft({ ...EMPTY })
      setSearchParams(previous => { const next = new URLSearchParams(previous); next.delete('onboard'); return next }, { replace: true })
    }
  }, [searchParams, can, setSearchParams])
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
    { key: 'name', header: 'Nama', sortValue: (t) => t.name, cell: (t) => <span><Text as="strong" weight="semibold">{t.name}</Text>{t.slug === 'platform' && <span className="muted entity-caption">{' · '}Akun sistem · bukan tenant pelanggan</span>}</span> },
    { key: 'slug', header: 'Slug', sortValue: (t) => t.slug, cell: (t) => t.slug },
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
        subtitle="Kelola organisasi pelanggan, akses admin, dan status langganannya."
        actions={
          can('platform.tenant.create') && (
            <Button variant="primary" onClick={() => openDraft({ ...EMPTY })}>
              <IconPlus size={15} /> Tambah tenant
            </Button>
          )
        }
      />

      {error && <div className="card load-error" role="alert"><p>{error}</p><Button onClick={() => void reload()}>Coba lagi</Button></div>}
      {notice && <p role="status" className="form-note">{notice}</p>}

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari nama atau slug…" />
        <SelectField aria-label="Filter status tenant" value={statusFilter} onChange={(_, data) => setStatusFilter(data.value)}>
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
        presentation="resource"
        rowActions={canSubscription || canManageTenant || canDeleteTenant ? rowActions : undefined}
        empty={
          <EmptyState
            title={query || statusFilter ? 'Tidak ada tenant yang cocok' : 'Belum ada tenant'}
            icon={<IconBuilding size={32} />}
          />
        }
      />

      <Blade
        creation={{ ...creation, busy: saving, prepare: () => (document.getElementById('tenant-onboarding') as HTMLFormElement | null)?.requestSubmit(), summary: <><CreationSummary rows={[['Tenant', draft?.name], ['Slug', draft?.slug]]} />{formError && <p className="error" role="alert">{formError}</p>}</> }}
        open={draft != null}
        title="Tambah tenant"
        size="sm"
        dirty={dirty}
        onClose={() => { if (!saving) closeDraft() }}
        footer={
          <>
            <Button disabled={saving} onClick={closeDraft}>Batal</Button>
            <Button
              variant="primary"
              type="submit"
              form="tenant-onboarding"
              disabled={saving}
            >
              {saving ? 'Menyimpan…' : 'Simpan'}
            </Button>
          </>
        }
      >
        {draft && (
          <form id="tenant-onboarding" className="stack" onSubmit={event => {
            event.preventDefault()
            if (saving) return
            if (creation.beforeSave()) return
            setSaving(true)
            setFormError(null)
            const { monthlyFee, ...rest } = draft
            void api.post('/api/platform/tenants', {
              ...rest, monthlyFee: monthlyFee.trim() === '' ? undefined : Number(monthlyFee),
            }).then(async () => {
              setNotice(`Tenant "${draft.slug}" siap. Admin bisa langsung masuk dengan tenant tersebut.`)
              closeDraft()
              await reload()
            }).catch(err => setFormError(err instanceof ApiError ? err.message : 'Gagal menyimpan tenant. Periksa data dan coba lagi.'))
              .finally(() => { setSaving(false); creation.finish() })
          }}>
            {formError && <p className="error" role="alert">{formError}</p>}
            <FormSection title="Identitas organisasi" description="Nama ditampilkan di aplikasi. Slug dipakai admin saat masuk ke tenant.">
              <div className="form-grid">
                <TextField required label="Nama" autoComplete="organization" value={draft.name} onChange={(_, data) => setDraft({ ...draft, name: data.value })} placeholder="PT Fiber Nusantara" />
                <TextField required label="Slug" hint="Huruf kecil, angka, dan tanda hubung." value={draft.slug} onChange={(_, data) => setDraft({ ...draft, slug: data.value })} placeholder="pt-fiber" />
              </div>
            </FormSection>
            <FormSection title="Admin pertama" description="Akun ini akan mengelola pengguna dan operasional tenant.">
              <TextField required label="Nama admin" autoComplete="name" value={draft.adminName} onChange={(_, data) => setDraft({ ...draft, adminName: data.value })} />
              <TextField required label="Email admin" type="email" autoComplete="email" value={draft.adminEmail} onChange={(_, data) => setDraft({ ...draft, adminEmail: data.value })} />
              <TextField required label="Password admin" type="password" autoComplete="new-password" value={draft.adminPassword} onChange={(_, data) => setDraft({ ...draft, adminPassword: data.value })} />
            </FormSection>
            <FormSection title="Langganan" description="Biaya khusus bersifat opsional. Kosongkan untuk mengikuti harga platform.">
              <TextField label="Harga bulanan khusus (Rp)" type="number" min={0} step="any" value={draft.monthlyFee} onChange={(_, data) => setDraft({ ...draft, monthlyFee: data.value })} placeholder={defaultFee != null ? `Default Rp ${defaultFee.toLocaleString('id-ID')}` : 'Gunakan harga default'} />
            </FormSection>
          </form>
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
              Hapus tenant <Text as="strong" weight="semibold">{confirmDelete.name}</Text> (<code>{confirmDelete.slug}</code>) secara permanen? Tenant yang memiliki riwayat transaksi atau aset gudang tidak dapat dihapus; gunakan Suspend. Penghapusan tenant kosong tidak bisa dibatalkan.
            </Text>
          }
        />
      )}
    </div>
  )
}
