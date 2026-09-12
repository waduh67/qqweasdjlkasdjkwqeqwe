import { useCallback, useEffect, useState } from 'react'
import { Text } from '@fluentui/react-components'
import { useNavigate } from 'react-router-dom'
import { ApiError } from '@/api/client'
import {
  ORDER_PORTAL_FLAG_LABEL,
  ORDER_STATUSES,
  ORDER_STATUS_LABEL,
  listOrders,
  type OrderPortalFlag,
  type OrderStatus,
  type OrderSummaryView,
} from '@/api/order'
import { useCan } from '@/auth/useCan'
import { Badge, Button, EmptyState, SelectField, Toolbar, type Tone } from '@/components/atoms'
import { IconInbox, IconUpload, IconUsers } from '@/components/atoms/icons'
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
  /**
   * `''` = semua, `'ANY'` = bertanda apa pun, sisanya = satu tanda tertentu.
   *
   * Satu kendali, bukan dua: "semua / bertanda / menunggu pelanggan / perlu perhatian" adalah satu
   * pertanyaan yang menyempit, dan memecahnya jadi sakelar + dropdown melahirkan kombinasi yang
   * saling bertentangan (sakelar mati + tanda terpilih) yang harus dijelaskan ke operator.
   */
  const [flagFilter, setFlagFilter] = useState<'' | 'ANY' | OrderPortalFlag>('')

  const canView = can('order.order.view')

  const reload = useCallback(async () => {
    setLoading(true)
    try {
      const response = await listOrders({
        query,
        status,
        // Penyaringnya dikerjakan SERVER. Menyaringnya di klien hanya akan menyaring 20 baris yang
        // kebetulan sedang tampil, jadi "hanya yang bertanda" memulangkan halaman setengah kosong
        // sementara pesanan bertanda di halaman lain tak pernah terlihat.
        flagged: flagFilter === 'ANY' ? true : undefined,
        portalFlag: flagFilter === 'ANY' ? '' : flagFilter,
        page,
        size: PAGE_SIZE,
      })
      setRows(response.content)
      setTotalPages(response.totalPages)
      setTotalElements(response.totalElements)
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Gagal memuat antrean pesanan')
    } finally {
      setLoading(false)
    }
  }, [query, status, flagFilter, page, toast])

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
  const changeFlagFilter = (value: '' | 'ANY' | OrderPortalFlag) => {
    setFlagFilter(value)
    setPage(0)
  }

  if (!canView) {
    return (
      <div className="card">
        <EmptyState title="Tak berizin" hint="Anda memerlukan izin order.order.view untuk melihat antrean pesanan." icon={<IconInbox size={32} />} />
      </div>
    )
  }

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
      key: 'flag',
      header: 'Penanda',
      sortValue: (order) => order.portalFlag ?? '',
      // Kolomnya SELALU ada sekarang, bukan muncul setelah ditekan tombol. Penanda yang harus
      // dicari dulu adalah penanda yang tak pernah dilihat siapa pun.
      cell: (order) =>
        order.portalFlag ? (
          <Badge tone={order.portalFlag === 'REQUIRES_ATTENTION' ? 'critical' : 'warning'}>
            {ORDER_PORTAL_FLAG_LABEL[order.portalFlag]}
          </Badge>
        ) : (
          <span className="muted">—</span>
        ),
    },
    {
      key: 'createdAt',
      header: 'Masuk',
      sortValue: (order) => order.createdAt,
      cell: (order) => fmt(order.createdAt),
    },
  ]

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
        <SelectField
          value={flagFilter}
          onChange={(_, data) => changeFlagFilter(data.value as '' | 'ANY' | OrderPortalFlag)}
          aria-label="Saring penanda"
        >
          <option value="">Semua penanda</option>
          <option value="ANY">Hanya yang bertanda</option>
          <option value="WAITING_CUSTOMER">{ORDER_PORTAL_FLAG_LABEL.WAITING_CUSTOMER}</option>
          <option value="REQUIRES_ATTENTION">{ORDER_PORTAL_FLAG_LABEL.REQUIRES_ATTENTION}</option>
        </SelectField>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(order) => order.id}
        loading={loading}
        presentation="resource"
        initialSort={{ key: 'createdAt', dir: 'desc' }}
        empty={
          <EmptyState
            title={query || status || flagFilter ? 'Tidak ada pesanan yang cocok' : 'Belum ada pesanan'}
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
