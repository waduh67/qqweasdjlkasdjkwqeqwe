import { useCallback, useEffect, useState } from 'react'
import { Text } from '@fluentui/react-components'
import { useNavigate } from 'react-router-dom'
import { ApiError } from '@/api/client'
import {
  LEAD_SOURCES,
  LEAD_SOURCE_LABEL,
  LEAD_STATUSES,
  LEAD_STATUS_LABEL,
  LEAD_STATUS_TARGETS,
  changeOrderLeadStatus,
  createOrderLead,
  listOrderLeads,
  promoteOrderLead,
  type LeadSource,
  type LeadStatus,
  type OrderLeadView,
} from '@/api/order'
import { useCan } from '@/auth/useCan'
import { Badge, Button, EmptyState, SelectField, TextField, TextareaField, Toolbar, type Tone } from '@/components/atoms'
import { IconInbox, IconPlus } from '@/components/atoms/icons'
import { ConfirmDialog, Modal, PageHeader, SearchInput } from '@/components/molecules'
import { DataTable, type Column } from '@/components/organisms'
import { useToast } from '@/system'

const PAGE_SIZE = 20

const STATUS_TONE: Record<LeadStatus, Tone> = {
  NEW: 'accent',
  CONTACTED: 'warning',
  QUALIFIED: 'good',
  CONVERTED: 'good',
  DROPPED: 'neutral',
}

const fmt = (iso: string) => new Date(iso).toLocaleString('id-ID')

type Draft = { name: string; phone: string; email: string; address: string; notes: string }

const EMPTY_DRAFT: Draft = { name: '', phone: '', email: '', address: '', notes: '' }

/**
 * Meja calon pelanggan (prospek): orang yang sudah memesan tapi BELUM jadi pelanggan.
 *
 * Mereka hidup terpisah dari modul pelanggan dengan sengaja — prospek yang tak pernah jadi
 * pelanggan dulu ikut terhitung di tagihan, langganan, dan laporan churn, dan membuat angkanya
 * bohong sejak hari pertama.
 */
export function OrderLeadsPage() {
  const { can } = useCan()
  const navigate = useNavigate()
  const toast = useToast()
  const [rows, setRows] = useState<OrderLeadView[]>([])
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [totalElements, setTotalElements] = useState(0)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState<LeadStatus | ''>('')
  const [source, setSource] = useState<LeadSource | ''>('')
  const [draft, setDraft] = useState<Draft | null>(null)
  const [statusTarget, setStatusTarget] = useState<{ lead: OrderLeadView; next: LeadStatus } | null>(null)
  const [promoting, setPromoting] = useState<OrderLeadView | null>(null)

  const canView = can('order.lead.view')
  const canManage = can('order.lead.manage')

  const reload = useCallback(async () => {
    setLoading(true)
    try {
      const response = await listOrderLeads({ query, status, source, page, size: PAGE_SIZE })
      setRows(response.content)
      setTotalPages(response.totalPages)
      setTotalElements(response.totalElements)
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Gagal memuat calon pelanggan')
    } finally {
      setLoading(false)
    }
  }, [query, status, source, page, toast])

  useEffect(() => {
    if (canView) void reload()
  }, [canView, reload])

  const run = async (action: () => Promise<unknown>, success: string): Promise<boolean> => {
    if (busy) return false
    setBusy(true)
    try {
      await action()
      await reload()
      toast.success(success)
      return true
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Aksi calon pelanggan gagal')
      return false
    } finally {
      setBusy(false)
    }
  }

  const submitDraft = () => {
    if (!draft) return
    if (!draft.name.trim() || !draft.phone.trim()) {
      toast.error('Nama dan nomor HP wajib diisi')
      return
    }
    void run(
      () =>
        createOrderLead({
          name: draft.name.trim(),
          phone: draft.phone.trim(),
          email: draft.email.trim() || null,
          address: draft.address.trim() || null,
          notes: draft.notes.trim() || null,
          source: 'OPERATOR',
        }),
      'Calon pelanggan dibuat',
    ).then((ok) => ok && setDraft(null))
  }

  if (!canView) {
    return (
      <div className="card">
        <EmptyState title="Tak berizin" hint="Anda memerlukan izin order.lead.view untuk melihat calon pelanggan." icon={<IconInbox size={32} />} />
      </div>
    )
  }

  const columns: Column<OrderLeadView>[] = [
    { key: 'name', header: 'Nama', sortValue: (lead) => lead.name, cell: (lead) => lead.name },
    { key: 'phone', header: 'HP', sortValue: (lead) => lead.phone, cell: (lead) => lead.phone },
    {
      key: 'address',
      header: 'Alamat',
      sortValue: (lead) => lead.address ?? '',
      cell: (lead) => lead.address ?? <span className="muted">—</span>,
    },
    { key: 'source', header: 'Sumber', sortValue: (lead) => lead.source, cell: (lead) => LEAD_SOURCE_LABEL[lead.source] },
    {
      key: 'status',
      header: 'Status',
      sortValue: (lead) => lead.status,
      cell: (lead) => <Badge tone={STATUS_TONE[lead.status]}>{LEAD_STATUS_LABEL[lead.status]}</Badge>,
    },
    { key: 'createdAt', header: 'Masuk', sortValue: (lead) => lead.createdAt, cell: (lead) => fmt(lead.createdAt) },
  ]

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <PageHeader
        title="Calon pelanggan"
        subtitle="Prospek yang sudah memesan tapi belum menjadi pelanggan."
        actions={
          <div className="row wrap">
            <Button variant="subtle" onClick={() => navigate('/orders')}>Antrean pesanan</Button>
            {canManage && (
              <Button variant="primary" onClick={() => setDraft({ ...EMPTY_DRAFT })}><IconPlus size={16} /> Tambah</Button>
            )}
          </div>
        }
      />

      <Toolbar>
        <SearchInput
          value={query}
          onChange={(value) => {
            setQuery(value)
            setPage(0)
          }}
          placeholder="Cari nama, HP, atau alamat…"
        />
        <SelectField
          value={status}
          aria-label="Saring status"
          onChange={(_, data) => {
            setStatus(data.value as LeadStatus | '')
            setPage(0)
          }}
        >
          <option value="">Semua status</option>
          {LEAD_STATUSES.map((value) => (
            <option key={value} value={value}>{LEAD_STATUS_LABEL[value]}</option>
          ))}
        </SelectField>
        <SelectField
          value={source}
          aria-label="Saring sumber"
          onChange={(_, data) => {
            setSource(data.value as LeadSource | '')
            setPage(0)
          }}
        >
          <option value="">Semua sumber</option>
          {LEAD_SOURCES.map((value) => (
            <option key={value} value={value}>{LEAD_SOURCE_LABEL[value]}</option>
          ))}
        </SelectField>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(lead) => lead.id}
        loading={loading}
        presentation="resource"
        initialSort={{ key: 'createdAt', dir: 'desc' }}
        rowActions={(lead) => [
          // Promosi melahirkan pelanggan + langganan sungguhan, jadi ia tak boleh jadi aksi
          // sekali klik tanpa konfirmasi — dan mati untuk prospek yang sudah dikonversi.
          {
            key: 'promote',
            label: 'Promosikan jadi pelanggan',
            disabled: !canManage || lead.status === 'CONVERTED' || lead.status === 'DROPPED',
            onClick: () => setPromoting(lead),
          },
          ...LEAD_STATUS_TARGETS[lead.status].map((next) => ({
            key: `status-${next}`,
            label: `Ubah status: ${LEAD_STATUS_LABEL[next]}`,
            disabled: !canManage,
            onClick: () => setStatusTarget({ lead, next }),
          })),
        ]}
        empty={
          <EmptyState
            title={query || status || source ? 'Tidak ada calon pelanggan yang cocok' : 'Belum ada calon pelanggan'}
            icon={<IconInbox size={32} />}
          />
        }
      />

      <div className="spread wrap">
        <Text as="span" className="muted" size={200}>
          {totalElements.toLocaleString('id-ID')} calon pelanggan · halaman {Math.min(page + 1, Math.max(totalPages, 1))} dari {Math.max(totalPages, 1)}
        </Text>
        <div className="row wrap">
          <Button variant="subtle" onClick={() => setPage((value) => Math.max(value - 1, 0))} disabled={loading || page === 0}>Sebelumnya</Button>
          <Button variant="subtle" onClick={() => setPage((value) => value + 1)} disabled={loading || page + 1 >= totalPages}>Berikutnya</Button>
        </div>
      </div>

      {draft && (
        <Modal
          title="Tambah calon pelanggan"
          onClose={() => setDraft(null)}
          footer={
            <>
              <Button variant="subtle" onClick={() => setDraft(null)} disabled={busy}>Batal</Button>
              <Button variant="primary" onClick={submitDraft} disabled={busy}>Simpan</Button>
            </>
          }
        >
          <TextField label="Nama" required value={draft.name} onChange={(_, data) => setDraft({ ...draft, name: data.value })} />
          <TextField
            label="Nomor HP"
            required
            hint="Satu-satunya kunci pelanggan untuk melacak pesanannya, dan satu-satunya jalan menelepon balik."
            value={draft.phone}
            onChange={(_, data) => setDraft({ ...draft, phone: data.value })}
          />
          <TextField label="Email (opsional)" value={draft.email} onChange={(_, data) => setDraft({ ...draft, email: data.value })} />
          <TextField label="Alamat (opsional)" value={draft.address} onChange={(_, data) => setDraft({ ...draft, address: data.value })} />
          <TextareaField label="Catatan (opsional)" rows={3} maxLength={1000} value={draft.notes} onChange={(_, data) => setDraft({ ...draft, notes: data.value })} />
        </Modal>
      )}

      {statusTarget && (
        <ConfirmDialog
          title="Ubah status calon pelanggan"
          message={`${statusTarget.lead.name} akan berstatus ${LEAD_STATUS_LABEL[statusTarget.next]}.`}
          confirmLabel="Ubah"
          busy={busy}
          onConfirm={() =>
            void run(() => changeOrderLeadStatus(statusTarget.lead.id, statusTarget.next), 'Status calon pelanggan diubah').then(
              (ok) => ok && setStatusTarget(null),
            )
          }
          onClose={() => setStatusTarget(null)}
        />
      )}

      {promoting && (
        <ConfirmDialog
          title="Promosikan jadi pelanggan"
          message={`${promoting.name} akan menjadi pelanggan beserta langganannya. Paket diambil dari paket yang diminati; bila kosong, server menolak dan Anda perlu menerimanya lewat pesanannya.`}
          confirmLabel="Promosikan"
          busy={busy}
          onConfirm={() =>
            void run(() => promoteOrderLead(promoting.id), 'Calon pelanggan dipromosikan').then((ok) => ok && setPromoting(null))
          }
          onClose={() => setPromoting(null)}
        />
      )}
    </div>
  )
}
