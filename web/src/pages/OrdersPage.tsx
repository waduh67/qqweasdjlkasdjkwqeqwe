import { useCallback, useEffect, useState } from 'react'
import { Text } from '@fluentui/react-components'
import { useNavigate } from 'react-router-dom'
import { ApiError } from '@/api/client'
import {
  ORDER_PORTAL_FLAG_LABEL,
  ORDER_STATUSES,
  ORDER_STATUS_LABEL,
  derivePortalFlag,
  getOrderTimeline,
  listOrders,
  type DerivedPortalFlag,
  type OrderStatus,
  type OrderSummaryView,
} from '@/api/order'
import { useCan } from '@/auth/useCan'
import { Badge, Button, EmptyState, SelectField, Toolbar, type Tone } from '@/components/atoms'
import { IconAlert, IconInbox, IconUpload, IconUsers } from '@/components/atoms/icons'
import { PageHeader, SearchInput } from '@/components/molecules'
import { DataTable, type Column } from '@/components/organisms'
import { useToast } from '@/system'

const PAGE_SIZE = 20

export const ORDER_STATUS_TONE: Record<OrderStatus, Tone> = {
  DRAFT: 'neutral',
  SUBMITTED: 'accent',
  ACCEPTED: 'accent',
  SCHEDULED: 'good',
  FULFILLING: 'warning',
  FULFILLED: 'good',
  CANCELLED: 'neutral',
  REJECTED: 'critical',
}

const fmt = (iso: string | null) => (iso ? new Date(iso).toLocaleString('id-ID') : '—')

/**
 * Antrean pesanan sisi OPERATOR.
 *
 * Pesanan masuk dari tiga pintu yang tak satu pun dijaga manusia: halaman publik, impor CSV,
 * dan operator sendiri. Tanpa layar ini pesanan dari portal publik hanya terlihat oleh
 * pelanggannya — yang membuka halaman lacak dan menunggu ditelepon oleh orang yang tak pernah
 * tahu pesanannya ada.
 */
export function OrdersPage() {
  const { can } = useCan()
  const navigate = useNavigate()
  const toast = useToast()
  const [rows, setRows] = useState<OrderSummaryView[]>([])
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [totalElements, setTotalElements] = useState(0)
  const [loading, setLoading] = useState(true)
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState<OrderStatus | ''>('')
  // Penanda portal per pesanan, hasil pindai riwayat. Lihat [scanFlags].
  const [flags, setFlags] = useState<Record<string, DerivedPortalFlag | null>>({})
  const [scanning, setScanning] = useState(false)
  const [scanned, setScanned] = useState(false)
  const [onlyFlagged, setOnlyFlagged] = useState(false)

  const canView = can('order.order.view')

  const reload = useCallback(async () => {
    setLoading(true)
    try {
      const response = await listOrders({ query, status, page, size: PAGE_SIZE })
      setRows(response.content)
      setTotalPages(response.totalPages)
      setTotalElements(response.totalElements)
      // Hasil pindai milik halaman SEBELUMNYA. Membiarkannya berarti baris baru mewarisi
      // penanda dari pesanan yang kebetulan menempati posisi yang sama.
      setFlags({})
      setScanned(false)
      setOnlyFlagged(false)
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Gagal memuat antrean pesanan')
    } finally {
      setLoading(false)
    }
  }, [query, status, page, toast])

  useEffect(() => {
    if (canView) void reload()
  }, [canView, reload])

  // Filter diubah lewat pembungkus ini, bukan lewat efek atas [query]/[status]: nomor halaman
  // HARUS ikut turun ke 0 dalam render yang sama. Kalau tidak, operator yang sedang di halaman 5
  // menyaring statusnya, melihat daftar kosong, dan mengira tak ada yang cocok.
  const changeQuery = (value: string) => {
    setQuery(value)
    setPage(0)
  }
  const changeStatus = (value: OrderStatus | '') => {
    setStatus(value)
    setPage(0)
  }

  /**
   * Memindai penanda portal untuk baris di halaman ini — SATU permintaan riwayat per baris.
   *
   * Mahal dan SENGAJA tidak otomatis: `GET /api/orders` tidak memulangkan `portalFlag` sama
   * sekali dan tak punya filter untuknya, jadi tak ada jalan lain untuk menjawab pertanyaan
   * yang paling sering ditanyakan operator — "mana pesanan yang sedang menunggu pelanggan?".
   * Begitu server menambahkan penanda ke baris antrean, seluruh blok ini HARUS dibuang.
   *
   * Dibatasi pada halaman yang sedang tampil (maksimal 20 baris), bukan seluruh hasil: memindai
   * antrean 800 pesanan berarti 800 permintaan yang membuat konsol tampak menggantung.
   */
  const scanFlags = async () => {
    if (scanning || rows.length === 0) return
    setScanning(true)
    try {
      const entries = await Promise.all(
        rows.map(async (row) => {
          try {
            return [row.id, derivePortalFlag(await getOrderTimeline(row.id))] as const
          } catch {
            // Satu riwayat yang gagal tidak boleh menggagalkan pindaian 19 baris lain;
            // barisnya sekadar tampil tanpa penanda.
            return [row.id, null] as const
          }
        }),
      )
      setFlags(Object.fromEntries(entries))
      setScanned(true)
    } finally {
      setScanning(false)
    }
  }

  if (!canView) {
    return (
      <div className="card">
        <EmptyState title="Tak berizin" hint="Anda memerlukan izin order.order.view untuk melihat antrean pesanan." icon={<IconInbox size={32} />} />
      </div>
    )
  }

  const visible = onlyFlagged ? rows.filter((row) => flags[row.id]) : rows

  const columns: Column<OrderSummaryView>[] = [
    {
      key: 'orderNumber',
      header: 'Nomor',
      sortValue: (order) => order.orderNumber,
      cell: (order) => order.orderNumber,
      onCellClick: (order) => navigate(`/orders/${order.id}`),
    },
    {
      key: 'requester',
      header: 'Pemesan',
      sortValue: (order) => order.requesterName ?? '',
      // Pesanan yatim (pemesannya tak bisa diresolusi lagi) tetap harus kelihatan, bukan
      // hilang diam-diam dari antrean — itulah pesanan yang paling butuh ditengok manusia.
      cell: (order) => order.requesterName ?? <span className="muted">Pemesan tak dikenal</span>,
    },
    {
      key: 'phone',
      header: 'HP',
      sortValue: (order) => order.requesterPhone ?? '',
      cell: (order) => order.requesterPhone ?? <span className="muted">—</span>,
    },
    {
      key: 'address',
      header: 'Alamat',
      sortValue: (order) => order.city,
      cell: (order) => `${order.address}, ${order.city}`,
    },
    {
      key: 'status',
      header: 'Status',
      sortValue: (order) => order.status,
      cell: (order) => <Badge tone={ORDER_STATUS_TONE[order.status]}>{ORDER_STATUS_LABEL[order.status]}</Badge>,
    },
    {
      key: 'createdAt',
      header: 'Masuk',
      sortValue: (order) => order.createdAt,
      cell: (order) => fmt(order.createdAt),
    },
  ]

  if (scanned) {
    columns.splice(5, 0, {
      key: 'flag',
      header: 'Penanda',
      sortValue: (order) => flags[order.id]?.flag ?? '',
      cell: (order) => {
        const flag = flags[order.id]
        if (!flag) return <span className="muted">—</span>
        return (
          <Badge tone={flag.flag === 'REQUIRES_ATTENTION' ? 'critical' : 'warning'}>
            {ORDER_PORTAL_FLAG_LABEL[flag.flag]}
          </Badge>
        )
      },
    })
  }

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <PageHeader
        title="Pesanan"
        subtitle="Antrean permintaan pemasangan dari portal publik, impor CSV, dan operator."
        actions={
          <div className="row wrap">
            {can('order.lead.view') && (
              <Button variant="subtle" onClick={() => navigate('/orders/leads')}><IconUsers size={16} /> Calon pelanggan</Button>
            )}
            {can('order.import.manage') && (
              <Button variant="subtle" onClick={() => navigate('/orders/import')}><IconUpload size={16} /> Impor CSV</Button>
            )}
          </div>
        }
      />

      <Toolbar>
        <SearchInput value={query} onChange={changeQuery} placeholder="Cari nomor, nama, HP, atau alamat…" />
        <SelectField value={status} onChange={(_, data) => changeStatus(data.value as OrderStatus | '')} aria-label="Saring status">
          <option value="">Semua status</option>
          {ORDER_STATUSES.map((s) => (
            <option key={s} value={s}>
              {ORDER_STATUS_LABEL[s]}
            </option>
          ))}
        </SelectField>
        <Button variant="subtle" onClick={() => void scanFlags()} disabled={scanning || loading || rows.length === 0}>
          <IconAlert size={16} /> {scanning ? 'Memeriksa…' : 'Periksa penanda perhatian'}
        </Button>
        {scanned && (
          <Button variant={onlyFlagged ? 'primary' : 'subtle'} onClick={() => setOnlyFlagged((value) => !value)}>
            Hanya yang bertanda
          </Button>
        )}
      </Toolbar>

      {scanned && (
        <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
          Penanda dibaca dari riwayat tiap pesanan di halaman ini saja; server belum menyertakannya di antrean.
          Pesanan bisa ditandai sistem (kunjungan gagal karena pelanggan, pemenuhan yang macet) maupun operator.
        </Text>
      )}

      <DataTable
        columns={columns}
        rows={visible}
        rowKey={(order) => order.id}
        loading={loading}
        presentation="resource"
        initialSort={{ key: 'createdAt', dir: 'desc' }}
        empty={
          <EmptyState
            title={query || status || onlyFlagged ? 'Tidak ada pesanan yang cocok' : 'Belum ada pesanan'}
            hint={onlyFlagged ? 'Tak ada pesanan bertanda di halaman ini.' : undefined}
            icon={<IconInbox size={32} />}
          />
        }
      />

      <div className="spread wrap">
        <Text as="span" className="muted" size={200}>
          {totalElements.toLocaleString('id-ID')} pesanan · halaman {Math.min(page + 1, Math.max(totalPages, 1))} dari {Math.max(totalPages, 1)}
        </Text>
        <div className="row wrap">
          <Button variant="subtle" onClick={() => setPage((value) => Math.max(value - 1, 0))} disabled={loading || page === 0}>
            Sebelumnya
          </Button>
          <Button variant="subtle" onClick={() => setPage((value) => value + 1)} disabled={loading || page + 1 >= totalPages}>
            Berikutnya
          </Button>
        </div>
      </div>
    </div>
  )
}
