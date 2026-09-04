import { useEffect, useMemo, useState } from 'react'
import { Table, TableBody, TableCell, TableHeader, TableHeaderCell, TableRow, Text, typographyStyles } from '@fluentui/react-components'
import { useNavigate } from 'react-router-dom'
import { ApiError } from '../api/client'
import {
  CUSTOMER_CSV_COLUMNS,
  cancelCustomerImport,
  commitCustomerImport,
  customerImportStatus,
  downloadCustomerImportReport,
  retryCustomerImport,
  stageCustomerImport,
  type CustomerImportCommitIdentity,
  type CustomerImportBatchState,
  type CustomerImportBatchView,
} from '../api/onboarding'
import { useCan } from '../auth/useCan'
import { Badge, Button, EmptyState, Spinner } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { IconDownload, IconInbox } from '@/components/atoms/icons'
import { useToast } from '@/system'

type ImportMode = CustomerImportBatchView['mode']

const BATCH_TONE: Record<CustomerImportBatchState, 'accent' | 'good' | 'neutral' | 'critical' | 'warning'> = {
  STAGED: 'accent',
  PROCESSING: 'warning',
  COMMITTED: 'good',
  CANCELLED: 'neutral',
  FAILED: 'critical',
  RETRYABLE_FAILED: 'warning',
  PERMANENT_FAILED: 'critical',
  PURGED: 'neutral',
}

const BATCH_LABEL: Record<CustomerImportBatchState, string> = {
  STAGED: 'Menunggu konfirmasi',
  PROCESSING: 'Sedang diproses',
  COMMITTED: 'Selesai',
  CANCELLED: 'Dibatalkan',
  FAILED: 'Perlu perhatian',
  RETRYABLE_FAILED: 'Dapat diulangi',
  PERMANENT_FAILED: 'Perlu perbaikan manual',
  PURGED: 'Sudah dihapus',
}

function createOperationKey(): string {
  return crypto.randomUUID()
}

function download(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

function downloadTemplate() {
  download(new Blob([`\uFEFF${CUSTOMER_CSV_COLUMNS.join(',')}\r\n`], { type: 'text/csv;charset=utf-8' }), 'template-pelanggan.csv')
}

export function ImportCustomersPage() {
  const { can } = useCan()
  const toast = useToast()
  const navigate = useNavigate()
  const [batch, setBatch] = useState<CustomerImportBatchView | null>(null)
  const [commitIdentity, setCommitIdentity] = useState<CustomerImportCommitIdentity | null>(null)
  const [mode, setMode] = useState<ImportMode>('ALREADY_INSTALLED')
  const [busy, setBusy] = useState(false)
  const canImport = can('customer.customer.create') && can('customer.customer.update') && can('customer.subscription.update') && can('bng.access.manage')
  const canCancel = can('customer.customer.update')

  useEffect(() => {
    if (!batch || batch.state !== 'PROCESSING') return
    let active = true
    const timeout = window.setTimeout(() => {
      void customerImportStatus(batch.id)
        .then((next) => active && setBatch(next))
        .catch((error) => active && toast.error(error instanceof ApiError ? error.message : 'Gagal memperbarui status impor'))
    }, 1500)
    return () => {
      active = false
      window.clearTimeout(timeout)
    }
  }, [batch, toast])

  const summary = useMemo(() => batch?.result ?? null, [batch])

  const stage = async (file: File) => {
    setBusy(true)
    try {
      const next = await stageCustomerImport(file, createOperationKey(), mode)
      setBatch(next)
      setCommitIdentity({ commitOperationKey: createOperationKey(), commitHash: next.sha256 })
      toast.success(next.errors.length === 0 ? 'Berkas disiapkan untuk ditinjau.' : 'Validasi server menemukan baris yang perlu diperbaiki.')
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Gagal mengunggah CSV')
    } finally {
      setBusy(false)
    }
  }

  const act = async (action: (id: string) => Promise<CustomerImportBatchView>, success: string) => {
    if (!batch || busy) return
    setBusy(true)
    try {
      const next = await action(batch.id)
      setBatch(next)
      toast.success(success)
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Aksi impor gagal')
    } finally {
      setBusy(false)
    }
  }

  const promote = async (
    action: (id: string, identity: CustomerImportCommitIdentity) => Promise<CustomerImportBatchView>,
    success: string,
  ) => {
    if (!batch || !commitIdentity || busy) return
    setBusy(true)
    try {
      const next = await action(batch.id, commitIdentity)
      setBatch(next)
      toast.success(success)
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Aksi impor gagal')
    } finally {
      setBusy(false)
    }
  }

  const downloadReport = async () => {
    if (!batch || busy) return
    setBusy(true)
    try {
      download(await downloadCustomerImportReport(batch.id), 'customer-import-report.csv')
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Gagal mengunduh rekap impor')
    } finally {
      setBusy(false)
    }
  }

  if (!canImport) {
    return <div className="card"><EmptyState title="Tak berizin" hint="Anda memerlukan izin untuk membuat dan mengubah pelanggan, langganan, serta akun jaringan." icon={<IconInbox size={32} />} /></div>
  }

  return (
    <div className="stack">
      <PageHeader
        title="Impor CSV pelanggan"
        subtitle="Unggah berkas ke server untuk divalidasi dan ditinjau sebelum perubahan dijalankan. Sistem tidak mengaktifkan atau mengirim perubahan jaringan langsung dari browser."
        actions={<Button variant="subtle" onClick={() => navigate('/customers')}>Kembali ke Pelanggan</Button>}
      />

      <div className="card stack">
        <div className="spread wrap">
          <Text as="h3" size={400} weight="semibold" style={{ margin: 0 }}>1. Unggah dan validasi server</Text>
          <Button variant="subtle" size="small" onClick={downloadTemplate}><IconDownload size={14} /> Unduh template</Button>
        </div>
        <label>
          <span>Mode pemenuhan</span>
          <select value={mode} onChange={(event) => setMode(event.target.value as ImportMode)} disabled={busy || !!batch}>
            <option value="ALREADY_INSTALLED">Pelanggan sudah terpasang</option>
            <option value="PENDING_INSTALLATION">Menunggu pemasangan</option>
            <option value="VALIDATE_ONLY">Validasi saja</option>
          </select>
        </label>
        <div className="row wrap">
          <input
            id="customer-csv-upload"
            type="file"
            accept=".csv,text/csv"
            disabled={busy || !!batch}
            onChange={(event) => {
              const file = event.target.files?.[0]
              if (file) void stage(file)
              event.target.value = ''
            }}
          />
          {busy && <Spinner />}
          {batch && <Text as="span" className="muted" size={200}>Berkas siap untuk ditinjau</Text>}
        </div>
        <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
          Header yang dikenali: {CUSTOMER_CSV_COLUMNS.filter((column) => !column.includes('password') && !column.includes('id_card')).join(', ')}. Nilai sensitif dalam berkas tidak ditampilkan kembali oleh halaman ini.
        </Text>
      </div>

      {batch && (
        <div className="card stack" aria-live="polite">
          <div className="spread wrap">
            <div className="row wrap"><Text as="h3" size={400} weight="semibold">2. Status batch</Text><Badge tone={BATCH_TONE[batch.state]}>{BATCH_LABEL[batch.state]}</Badge></div>
            <Button variant="subtle" size="small" onClick={() => void downloadReport()} disabled={busy || batch.state === 'PURGED'}><IconDownload size={14} /> Unduh rekap aman</Button>
          </div>
          <Text as="p" className="muted" size={200} style={{ margin: 0 }}>Mode: {modeLabel(batch.mode)}. Validasi dan hasil akhir ditetapkan server.</Text>
          <BatchSummary result={summary} />
          {batch.errors.length > 0 && <BatchErrors errors={batch.errors} />}
          <div className="row wrap">
            <Button variant="primary" onClick={() => void promote(commitCustomerImport, 'Batch dikirim untuk diproses.')} disabled={busy || batch.state !== 'STAGED' || batch.errors.length > 0}>Jalankan impor</Button>
            <Button variant="danger" onClick={() => void act(cancelCustomerImport, 'Batch dibatalkan.')} disabled={busy || !canCancel || batch.state !== 'STAGED'}>Batalkan batch</Button>
            <Button variant="default" onClick={() => void promote(retryCustomerImport, 'Batch dikirim ulang.')} disabled={busy || batch.state !== 'RETRYABLE_FAILED'}>Ulangi batch</Button>
            {batch.state === 'CANCELLED' && <Button variant="subtle" onClick={() => setBatch(null)}>Unggah berkas lain</Button>}
            {(batch.state === 'PERMANENT_FAILED' || batch.state === 'PURGED') && <Button variant="subtle" onClick={() => setBatch(null)}>Unggah berkas baru</Button>}
          </div>
          {batch.state === 'RETRYABLE_FAILED' && <Text as="p" className="muted" size={200} style={{ margin: 0 }}>Pemrosesan dapat diulangi dengan identitas commit yang sama.</Text>}
          {batch.state === 'PERMANENT_FAILED' && <Text as="p" className="muted" size={200} style={{ margin: 0 }}>Batch tidak dapat diulangi. Perbaiki sumber data lalu unggah berkas baru.</Text>}
          {batch.state === 'PURGED' && <Text as="p" className="muted" size={200} style={{ margin: 0 }}>Data batch telah melewati retensi dan tidak lagi tersedia.</Text>}
        </div>
      )}
    </div>
  )
}

function modeLabel(mode: ImportMode): string {
  const labels: Record<ImportMode, string> = {
    ALREADY_INSTALLED: 'Pelanggan sudah terpasang',
    PENDING_INSTALLATION: 'Menunggu pemasangan',
    VALIDATE_ONLY: 'Validasi saja',
  }
  return labels[mode]
}

function BatchSummary({ result }: { result: CustomerImportBatchView['result'] }) {
  if (!result) return null
  return <div className="row wrap"><Badge tone="good">{result.created} dibuat</Badge><Badge tone="accent">{result.updated} diperbarui</Badge><Badge tone="neutral">{result.skipped} dilewati</Badge><Badge tone="critical">{result.failed} gagal</Badge></div>
}

function BatchErrors({ errors }: { errors: CustomerImportBatchView['errors'] }) {
  return (
    <div className="table-wrap">
      <Table style={typographyStyles.body1} aria-label="Galat validasi CSV">
        <TableHeader><TableRow><TableHeaderCell>Baris</TableHeaderCell><TableHeaderCell>Kolom</TableHeaderCell><TableHeaderCell>Kode</TableHeaderCell></TableRow></TableHeader>
        <TableBody>{errors.map((error, index) => <TableRow key={`${error.row}-${error.column}-${index}`}><TableCell>{error.row}</TableCell><TableCell>{error.column ?? 'Berkas'}</TableCell><TableCell><Badge tone="critical">{error.code}</Badge></TableCell></TableRow>)}</TableBody>
      </Table>
    </div>
  )
}
