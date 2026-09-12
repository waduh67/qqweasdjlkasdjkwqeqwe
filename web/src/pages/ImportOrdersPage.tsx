import { useCallback, useEffect, useState } from 'react'
import { Table, TableBody, TableCell, TableHeader, TableHeaderCell, TableRow, Text, typographyStyles } from '@fluentui/react-components'
import { useNavigate } from 'react-router-dom'
import { ApiError } from '@/api/client'
import {
  ORDER_IMPORT_BATCH_STATUS_LABEL,
  ORDER_IMPORT_COLUMNS,
  ORDER_IMPORT_MAX_BYTES,
  ORDER_IMPORT_MAX_ROWS,
  ORDER_IMPORT_ROW_STATUS_LABEL,
  commitOrderImport,
  downloadOrderImportTemplate,
  listOrderImportHistory,
  previewOrderImport,
  type OrderImportBatchDetailView,
  type OrderImportBatchSummaryView,
  type OrderImportRowStatus,
} from '@/api/order'
import { useCan } from '@/auth/useCan'
import { Badge, Button, EmptyState, Spinner, type Tone } from '@/components/atoms'
import { IconDownload, IconInbox } from '@/components/atoms/icons'
import { ConfirmDialog, PageHeader } from '@/components/molecules'
import { useToast } from '@/system'
import { downloadBlob } from '@/utils/download'

const ROW_TONE: Record<OrderImportRowStatus, Tone> = {
  ACCEPTED: 'accent',
  REJECTED: 'critical',
  DUPLICATE: 'warning',
  CREATED: 'good',
  FAILED: 'critical',
}

const fmt = (iso: string | null) => (iso ? new Date(iso).toLocaleString('id-ID') : '—')

const kib = (bytes: number) => `${Math.round(bytes / 1024).toLocaleString('id-ID')} KiB`

/**
 * Impor massal pesanan dari CSV — DUA langkah: pratinjau lalu jalankan.
 *
 * Halaman ini sengaja tidak punya satu pun tombol yang langsung membuat pesanan dari berkas.
 * Impor 500 baris yang menulis separuh lalu berhenti adalah bencana tanpa tombol batal: tak ada
 * cara membatalkan 237 pesanan yang terlanjur masuk antrean, dan tak ada cara tahu baris ke
 * berapa ia berhenti. Pratinjau memberi operator kesempatan terakhir untuk membaca vonis per
 * baris sebelum apa pun lahir.
 *
 * Pola alurnya mengikuti `ImportCustomersPage`.
 */
export function ImportOrdersPage() {
  const { can } = useCan()
  const navigate = useNavigate()
  const toast = useToast()
  const [batch, setBatch] = useState<OrderImportBatchDetailView | null>(null)
  const [history, setHistory] = useState<OrderImportBatchSummaryView[]>([])
  const [busy, setBusy] = useState(false)
  const [confirming, setConfirming] = useState(false)

  const canImport = can('order.import.manage')

  const reloadHistory = useCallback(async () => {
    try {
      setHistory(await listOrderImportHistory(20))
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Gagal memuat riwayat impor')
    }
  }, [toast])

  useEffect(() => {
    if (canImport) void reloadHistory()
  }, [canImport, reloadHistory])

  /**
   * Ukuran diperiksa DI SINI, sebelum berkasnya dikirim. Operator di jaringan kantor yang
   * mengunggah .xlsx 5 MB akan menunggu satu menit hanya untuk dibalas "melebihi batas" —
   * kesalahan yang sudah bisa diketahui sebelum byte pertama berangkat.
   */
  const preview = async (file: File) => {
    if (file.size > ORDER_IMPORT_MAX_BYTES) {
      toast.error(`Berkas ${kib(file.size)} melebihi batas ${kib(ORDER_IMPORT_MAX_BYTES)}. Pastikan yang dipilih CSV, bukan Excel (.xlsx).`)
      return
    }
    setBusy(true)
    try {
      const detail = await previewOrderImport(file)
      setBatch(detail)
      toast.success(
        detail.batch.rejectedRows === 0
          ? 'Berkas terbaca. Periksa pratinjau sebelum menjalankan impor.'
          : `${detail.batch.rejectedRows} baris ditolak. Perbaiki berkasnya atau jalankan hanya baris yang lolos.`,
      )
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Gagal membaca berkas CSV')
    } finally {
      setBusy(false)
    }
  }

  const commit = async () => {
    if (!batch) return
    setBusy(true)
    try {
      setBatch(await commitOrderImport(batch.batch.id))
      await reloadHistory()
      toast.success('Impor dijalankan.')
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Impor gagal dijalankan')
    } finally {
      setBusy(false)
      setConfirming(false)
    }
  }

  const template = async () => {
    setBusy(true)
    try {
      downloadBlob(await downloadOrderImportTemplate(), 'contoh-impor-pesanan.csv')
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Gagal mengunduh berkas contoh')
    } finally {
      setBusy(false)
    }
  }

  if (!canImport) {
    return (
      <div className="card">
        <EmptyState title="Tak berizin" hint="Anda memerlukan izin order.import.manage untuk mengimpor pesanan." icon={<IconInbox size={32} />} />
      </div>
    )
  }

  const previewed = batch?.batch.status === 'PREVIEWED'

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <PageHeader
        title="Impor pesanan dari CSV"
        subtitle="Unggah berkas untuk diurai dan ditinjau. Tidak ada pesanan yang dibuat sebelum Anda menjalankan pratinjaunya."
        actions={<Button variant="subtle" onClick={() => navigate('/orders')}>Kembali ke antrean</Button>}
      />

      <div className="card stack">
        <div className="spread wrap">
          <Text as="h3" style={{ ...typographyStyles.subtitle2, margin: 0 }}>1. Unggah dan pratinjau</Text>
          <Button variant="subtle" size="small" onClick={() => void template()} disabled={busy}>
            <IconDownload size={14} /> Unduh berkas contoh
          </Button>
        </div>
        <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
          Batas server: maksimal {kib(ORDER_IMPORT_MAX_BYTES)} per berkas dan {ORDER_IMPORT_MAX_ROWS.toLocaleString('id-ID')} baris data.
        </Text>
        <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
          Kolom yang dikenali: {ORDER_IMPORT_COLUMNS.map((column) => (column.required ? `${column.label} (wajib)` : column.label)).join(', ')}.
        </Text>
        <div className="row wrap">
          <input
            id="order-csv-upload"
            type="file"
            accept=".csv,text/csv"
            disabled={busy}
            onChange={(event) => {
              const file = event.target.files?.[0]
              if (file) void preview(file)
              // Dikosongkan supaya memilih berkas yang SAMA lagi (setelah diperbaiki di Excel)
              // tetap memicu `change`; tanpa ini unggahan kedua tampak tak terjadi apa-apa.
              event.target.value = ''
            }}
          />
          {busy && <Spinner />}
        </div>
      </div>

      {batch && (
        <div className="card stack" aria-live="polite">
          <div className="spread wrap">
            <div className="row wrap" style={{ alignItems: 'center', gap: '0.5rem' }}>
              <Text as="h3" style={{ ...typographyStyles.subtitle2, margin: 0 }}>2. Pratinjau {batch.batch.fileName}</Text>
              <Badge tone={previewed ? 'accent' : 'good'}>{ORDER_IMPORT_BATCH_STATUS_LABEL[batch.batch.status]}</Badge>
            </div>
            <Button variant="subtle" size="small" onClick={() => setBatch(null)} disabled={busy}>Unggah berkas lain</Button>
          </div>
          <div className="row wrap">
            <Badge tone="neutral">{batch.batch.totalRows} baris</Badge>
            <Badge tone="accent">{batch.batch.acceptedRows} siap dibuat</Badge>
            <Badge tone="critical">{batch.batch.rejectedRows} ditolak</Badge>
            <Badge tone="good">{batch.batch.createdRows} dibuat</Badge>
            <Badge tone="critical">{batch.batch.failedRows} gagal</Badge>
          </div>
          <ImportRows batch={batch} />
          <div className="row wrap">
            <Button
              variant="primary"
              disabled={busy || !previewed || batch.batch.acceptedRows === 0}
              onClick={() => setConfirming(true)}
            >
              Jalankan {batch.batch.acceptedRows} baris
            </Button>
            {!previewed && (
              <Text as="span" className="muted" size={200}>Batch ini sudah dijalankan; menjalankannya lagi tidak membuat pesanan kedua.</Text>
            )}
            {previewed && batch.batch.acceptedRows === 0 && (
              <Text as="span" className="muted" size={200}>Tak ada baris yang lolos. Perbaiki berkasnya lalu unggah ulang.</Text>
            )}
          </div>
        </div>
      )}

      <div className="card stack">
        <Text as="h3" style={{ ...typographyStyles.subtitle2, margin: 0 }}>Riwayat impor</Text>
        {history.length === 0 ? (
          <Text as="p" className="muted" size={200} style={{ margin: 0 }}>Belum ada berkas yang pernah diimpor.</Text>
        ) : (
          <div className="table-wrap">
            <Table style={typographyStyles.body1} aria-label="Riwayat impor pesanan">
              <TableHeader>
                <TableRow>
                  <TableHeaderCell>Berkas</TableHeaderCell>
                  <TableHeaderCell>Status</TableHeaderCell>
                  <TableHeaderCell>Baris</TableHeaderCell>
                  <TableHeaderCell>Dibuat</TableHeaderCell>
                  <TableHeaderCell>Diunggah</TableHeaderCell>
                </TableRow>
              </TableHeader>
              <TableBody>
                {history.map((entry) => (
                  <TableRow key={entry.id}>
                    <TableCell>{entry.fileName}</TableCell>
                    <TableCell><Badge tone={entry.status === 'COMMITTED' ? 'good' : 'accent'}>{ORDER_IMPORT_BATCH_STATUS_LABEL[entry.status]}</Badge></TableCell>
                    <TableCell>{entry.totalRows}</TableCell>
                    <TableCell>{entry.createdRows}</TableCell>
                    <TableCell>{fmt(entry.createdAt)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </div>

      {confirming && batch && (
        <ConfirmDialog
          title="Jalankan impor"
          message={`${batch.batch.acceptedRows} pesanan dan calon pelanggan akan dibuat dari ${batch.batch.fileName}. Baris yang ditolak dilewati.`}
          confirmLabel="Jalankan"
          busy={busy}
          onConfirm={() => void commit()}
          onClose={() => setConfirming(false)}
        />
      )}
    </div>
  )
}

/**
 * Vonis per baris. [message] ditampilkan APA ADANYA — ia satu-satunya petunjuk kenapa satu baris
 * ditolak, dan tanpanya operator hanya tahu "ada 37 baris gagal" tanpa tahu harus memperbaiki apa.
 */
function ImportRows({ batch }: { batch: OrderImportBatchDetailView }) {
  if (batch.rows.length === 0) {
    return <Text as="p" className="muted" size={200} style={{ margin: 0 }}>Berkas tidak memuat baris data.</Text>
  }
  return (
    <div className="table-wrap">
      <Table style={typographyStyles.body1} aria-label="Pratinjau baris impor">
        <TableHeader>
          <TableRow>
            <TableHeaderCell>Baris</TableHeaderCell>
            <TableHeaderCell>Nama</TableHeaderCell>
            <TableHeaderCell>HP</TableHeaderCell>
            <TableHeaderCell>Alamat</TableHeaderCell>
            <TableHeaderCell>Status</TableHeaderCell>
            <TableHeaderCell>Keterangan</TableHeaderCell>
          </TableRow>
        </TableHeader>
        <TableBody>
          {batch.rows.map((row) => (
            <TableRow key={row.id}>
              <TableCell>{row.lineNumber}</TableCell>
              <TableCell>{row.name ?? '—'}</TableCell>
              <TableCell>{row.phone ?? '—'}</TableCell>
              <TableCell>{[row.address, row.city].filter(Boolean).join(', ') || '—'}</TableCell>
              <TableCell><Badge tone={ROW_TONE[row.status]}>{ORDER_IMPORT_ROW_STATUS_LABEL[row.status]}</Badge></TableCell>
              <TableCell>{row.orderNumber ?? row.message ?? '—'}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}
