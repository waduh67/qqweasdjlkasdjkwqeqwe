import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError } from '../api/client'
import {
  CUSTOMER_CSV_COLUMNS,
  importCustomers,
  type CustomerCsvRow,
  type CustomerImportStatus,
  type ImportCustomersResult,
} from '../api/onboarding'
import { useCan } from '../auth/useCan'
import { Badge, EmptyState, useToast } from '../components/ui'
import { IconInbox, IconDownload, IconUpload } from '../components/icons'

/**
 * Impor CSV pelanggan — unggah satu berkas berisi biodata + langganan + akun jaringan, di-UPSERT
 * menurut `mikrotik_username`. Berbeda dari Impor PPPoE (menyedot `/ppp/secret` sebuah router):
 * sumbernya berkas CSV yang dirakit operator (atau hasil ekspor sistem ini, sehingga bisa
 * di-round-trip). Alur: unggah → urai di klien → pratinjau → commit → rekap per-baris.
 *
 * Penguraian CSV terjadi di browser (RFC-4180, dipetakan lewat NAMA header sehingga urutan kolom
 * bebas). Baris tanpa `mikrotik_username` atau bertipe koneksi non-PPPoE ditandai "akan dilewati"
 * di pratinjau — server tetap penegak sebenarnya dan melaporkannya kembali di rekap.
 */

/** Satu baris pratinjau: muatan siap-kirim + alasan lewat yang diprediksi klien (jika ada). */
type PreviewRow = CustomerCsvRow & { skipReason: string | null }

/**
 * Urai teks CSV jadi larik-baris (RFC-4180): field boleh dibungkus kutip; kutip ganda `""` jadi satu
 * kutip; koma/baris-baru di dalam kutip bukan pemisah. Menangani akhir baris `\r\n` maupun `\n`.
 */
function parseCsvRecords(text: string): string[][] {
  const src = text.charCodeAt(0) === 0xfeff ? text.slice(1) : text // buang BOM Excel
  const records: string[][] = []
  let row: string[] = []
  let field = ''
  let inQuotes = false
  let i = 0
  const pushField = () => {
    row.push(field)
    field = ''
  }
  const pushRow = () => {
    pushField()
    records.push(row)
    row = []
  }
  while (i < src.length) {
    const c = src[i]
    if (inQuotes) {
      if (c === '"') {
        if (src[i + 1] === '"') {
          field += '"'
          i += 2
          continue
        }
        inQuotes = false
        i++
        continue
      }
      field += c
      i++
      continue
    }
    if (c === '"') {
      inQuotes = true
      i++
      continue
    }
    if (c === ',') {
      pushField()
      i++
      continue
    }
    if (c === '\r') {
      i++
      continue
    }
    if (c === '\n') {
      pushRow()
      i++
      continue
    }
    field += c
    i++
  }
  // Baris terakhir tanpa newline penutup.
  if (field.length > 0 || row.length > 0) pushRow()
  // Buang baris yang seluruh selnya kosong (baris hampa di tengah/akhir berkas).
  return records.filter((r) => r.some((cell) => cell.trim() !== ''))
}

/** Normalisasi tanggal ke ISO `YYYY-MM-DD`. Terima ISO, `DD/MM/YYYY`, `DD-MM-YYYY`, `YYYY/MM/DD`. Tak terbaca → null. */
function normalizeDate(raw: string): string | null {
  const s = raw.trim()
  if (!s) return null
  if (/^\d{4}-\d{2}-\d{2}$/.test(s)) return s
  const ymd = s.match(/^(\d{4})\/(\d{1,2})\/(\d{1,2})$/)
  if (ymd) return `${ymd[1]}-${ymd[2].padStart(2, '0')}-${ymd[3].padStart(2, '0')}`
  const dmy = s.match(/^(\d{1,2})[/-](\d{1,2})[/-](\d{4})$/)
  if (dmy) return `${dmy[3]}-${dmy[2].padStart(2, '0')}-${dmy[1].padStart(2, '0')}`
  return null
}

/** Angka desimal — terima koma sebagai titik desimal; kosong/tak terbaca → null. */
function toNum(raw: string): number | null {
  const s = raw.trim().replace(',', '.')
  if (!s) return null
  const n = Number(s)
  return Number.isFinite(n) ? n : null
}

function toInt(raw: string): number | null {
  const n = toNum(raw)
  return n === null ? null : Math.trunc(n)
}

/** Kosong → null; selain itu trim. Menjaga muatan ramping (kolom kosong = "pertahankan" saat upsert). */
function orNull(raw: string): string | null {
  const s = raw.trim()
  return s === '' ? null : s
}

/** PPPoE bila tipe kosong atau memuat substring "pppoe" (cermin prediksi server; sisanya dilewati v1). */
function isPppoe(connectionType: string | null | undefined): boolean {
  return !connectionType || connectionType.toLowerCase().includes('pppoe')
}

/** Petakan larik-baris CSV ber-header jadi baris pratinjau. Kolom dicocokkan lewat NAMA header. */
function toPreviewRows(records: string[][]): PreviewRow[] {
  if (records.length === 0) return []
  const [header, ...body] = records
  const idx: Record<string, number> = {}
  header.forEach((h, i) => {
    idx[h.trim().toLowerCase()] = i
  })
  const at = (cols: string[], name: string): string => {
    const i = idx[name]
    return i === undefined ? '' : (cols[i] ?? '')
  }
  return body.map((cols) => {
    const mikrotikUsername = orNull(at(cols, 'mikrotik_username'))
    const connectionType = orNull(at(cols, 'connection_type'))
    const skipReason = !mikrotikUsername
      ? 'username kosong'
      : !isPppoe(connectionType)
        ? 'tipe koneksi non-PPPoE'
        : null
    return {
      name: orNull(at(cols, 'name')),
      phone: orNull(at(cols, 'phone')),
      address: orNull(at(cols, 'address')),
      packageName: orNull(at(cols, 'package_name')),
      connectionType,
      installationDate: normalizeDate(at(cols, 'installation_date')),
      mikrotikUsername,
      mikrotikPassword: orNull(at(cols, 'mikrotik_password')),
      email: orNull(at(cols, 'email')),
      routerName: orNull(at(cols, 'router_name')),
      idCardNumber: orNull(at(cols, 'id_card_number')),
      nextBillingDay: toInt(at(cols, 'next_billing')),
      latitude: toNum(at(cols, 'latitude')),
      longitude: toNum(at(cols, 'longitude')),
      skipReason,
    }
  })
}

/** Unduh Blob teks sebagai berkas di browser. */
function triggerDownload(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

/** Template CSV: hanya baris header kanonis (+ BOM agar Excel membaca UTF-8). */
function downloadTemplate() {
  const csv = `${CUSTOMER_CSV_COLUMNS.join(',')}\r\n`
  triggerDownload(new Blob([`﻿${csv}`], { type: 'text/csv;charset=utf-8' }), 'template-pelanggan.csv')
}

export function ImportCustomersPage() {
  const { can } = useCan()
  const toast = useToast()
  const navigate = useNavigate()

  const [fileName, setFileName] = useState('')
  const [rows, setRows] = useState<PreviewRow[]>([])
  const [saving, setSaving] = useState(false)
  const [result, setResult] = useState<ImportCustomersResult | null>(null)

  const canImport =
    can('customer.customer.create') &&
    can('customer.customer.update') &&
    can('customer.subscription.update') &&
    can('bng.access.manage')

  const skipCount = useMemo(() => rows.filter((r) => r.skipReason).length, [rows])
  const readyCount = rows.length - skipCount

  const onFile = (file: File) => {
    setResult(null)
    void file.text().then((text) => {
      const parsed = toPreviewRows(parseCsvRecords(text))
      if (parsed.length === 0) {
        toast.error('Berkas kosong atau hanya berisi header. Pastikan ada baris data.')
        setRows([])
        setFileName('')
        return
      }
      setRows(parsed)
      setFileName(file.name)
      toast.success(`${parsed.length} baris terbaca.`)
    })
  }

  const submit = async () => {
    if (rows.length === 0) return
    setSaving(true)
    try {
      // Kirim SEMUA baris (termasuk yang diprediksi dilewati) agar rekap server jujur & lengkap.
      const payload: CustomerCsvRow[] = rows.map(({ skipReason: _skip, ...row }) => row)
      const res = await importCustomers({ rows: payload })
      setResult(res)
      toast.success(
        `Impor selesai: ${res.created} dibuat, ${res.updated} diperbarui, ${res.skipped} dilewati, ${res.failed} gagal.`,
      )
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menjalankan impor')
    } finally {
      setSaving(false)
    }
  }

  if (!canImport) {
    return (
      <div className="card">
        <EmptyState
          title="Tak berizin"
          hint="Butuh izin membuat & mengubah pelanggan, mengubah langganan, dan mengelola akun jaringan untuk impor CSV."
          icon={<IconInbox size={32} />}
        />
      </div>
    )
  }

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <div className="spread" style={{ alignItems: 'flex-start' }}>
        <div>
          <h1 className="page-title">Impor CSV pelanggan</h1>
          <p className="page-sub">
            Unggah CSV berisi biodata + paket + akun jaringan. Baris di-UPSERT menurut{' '}
            <code>mikrotik_username</code>: belum ada → dibuat &amp; langsung aktif; sudah ada → diperbarui
            (kolom kosong dipertahankan). Password kosong = pertahankan yang lama.
          </p>
        </div>
        <button className="ghost" onClick={() => navigate('/customers')}>
          Kembali ke Pelanggan
        </button>
      </div>

      {/* 1. Unggah berkas */}
      <div className="card stack" style={{ gap: '0.8rem' }}>
        <div className="spread" style={{ alignItems: 'center' }}>
          <h3 style={{ margin: 0, fontSize: '0.95rem' }}>1. Unggah berkas CSV</h3>
          <button className="ghost small" onClick={downloadTemplate}>
            <IconDownload size={14} /> Unduh template
          </button>
        </div>
        <div className="row" style={{ gap: '0.5rem', alignItems: 'center' }}>
          <input
            id="customer-csv-upload"
            type="file"
            accept=".csv,text/csv"
            style={{ display: 'none' }}
            onChange={(e) => {
              const f = e.target.files?.[0]
              if (f) onFile(f)
              e.target.value = '' // izinkan mengunggah berkas yang sama lagi
            }}
          />
          <label
            htmlFor="customer-csv-upload"
            className="ghost"
            style={{ cursor: 'pointer', padding: '0.4rem 0.7rem', borderRadius: 6, border: '1px solid var(--border)' }}
          >
            <IconUpload size={14} /> Pilih berkas…
          </label>
          {fileName && (
            <span className="muted" style={{ fontSize: '0.85rem' }}>
              <code>{fileName}</code> — {rows.length} baris
            </span>
          )}
        </div>
        <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>
          Kolom dicocokkan lewat nama header (urutan bebas): {CUSTOMER_CSV_COLUMNS.join(', ')}. Hasil{' '}
          <strong>Ekspor CSV</strong> dari halaman Pelanggan bisa langsung diunggah kembali.
        </p>
        <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>
          <code>connection_type</code>: isi <code>pppoe</code> (atau kosongkan) — v1 hanya memproses PPPoE;
          tipe lain (<code>hotspot</code>, <code>static</code>, <code>dhcp</code>) akan dilewati.
          <br />
          <code>installation_date</code>: <code>YYYY-MM-DD</code> (juga menerima <code>DD/MM/YYYY</code>).{' '}
          <code>next_billing</code>: tanggal tagih 1–28. <code>mikrotik_password</code> kosong = pertahankan
          yang lama.
        </p>
      </div>

      {/* 2. Pratinjau */}
      {rows.length > 0 && (
        <div className="card stack" style={{ gap: '0.6rem' }}>
          <div className="spread" style={{ alignItems: 'center' }}>
            <h3 style={{ margin: 0, fontSize: '0.95rem' }}>
              2. Pratinjau{' '}
              <span className="muted">
                ({readyCount} siap{skipCount > 0 ? `, ${skipCount} akan dilewati` : ''})
              </span>
            </h3>
          </div>
          <div style={{ maxHeight: 360, overflow: 'auto' }}>
            <table className="table" style={{ fontSize: '0.85rem' }}>
              <thead>
                <tr>
                  <th>Username</th>
                  <th>Nama</th>
                  <th>Paket</th>
                  <th>Koneksi</th>
                  <th>Tgl pasang</th>
                  <th>Tagih</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r, i) => (
                  <tr key={i}>
                    <td>{r.mikrotikUsername ? <code>{r.mikrotikUsername}</code> : <span className="muted">—</span>}</td>
                    <td>{r.name ?? <span className="muted">—</span>}</td>
                    <td>{r.packageName ?? <span className="muted">—</span>}</td>
                    <td>{r.connectionType ?? <span className="muted">pppoe</span>}</td>
                    <td>{r.installationDate ?? <span className="muted">—</span>}</td>
                    <td>{r.nextBillingDay ?? <span className="muted">—</span>}</td>
                    <td>
                      {r.skipReason ? (
                        <Badge tone="neutral">dilewati · {r.skipReason}</Badge>
                      ) : (
                        <Badge tone="good">siap</Badge>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {skipCount > 0 && (
            <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>
              {skipCount} baris akan dilewati server (username kosong atau tipe koneksi non-PPPoE). v1 hanya
              memproses PPPoE.
            </p>
          )}
        </div>
      )}

      {/* 3. Commit */}
      {rows.length > 0 && (
        <div className="row" style={{ gap: '0.5rem' }}>
          <button className="primary" onClick={() => void submit()} disabled={saving || readyCount === 0}>
            <IconInbox size={15} /> {saving ? 'Mengimpor…' : `Impor ${readyCount} baris`}
          </button>
          <span className="muted" style={{ fontSize: '0.8rem', alignSelf: 'center' }}>
            Baris baru langsung aktif &amp; ditulis ke RADIUS. Aktivasi memprorata tagihan dari tanggal pasang.
          </span>
        </div>
      )}

      {result && <ResultCard result={result} onDismiss={() => setResult(null)} />}
    </div>
  )
}

const STATUS_TONE: Record<CustomerImportStatus, 'good' | 'accent' | 'neutral' | 'critical'> = {
  CREATED: 'good',
  UPDATED: 'accent',
  SKIPPED: 'neutral',
  FAILED: 'critical',
}

/** Rekap hasil impor + rincian per-baris (username, status, pesan). */
function ResultCard({ result, onDismiss }: { result: ImportCustomersResult; onDismiss: () => void }) {
  return (
    <div className="card stack" style={{ gap: '0.6rem', borderLeft: '3px solid var(--good)' }}>
      <div className="spread" style={{ alignItems: 'center' }}>
        <h3 style={{ margin: 0, fontSize: '0.95rem' }}>
          Hasil impor — <Badge tone="good">{result.created} dibuat</Badge>{' '}
          <Badge tone="accent">{result.updated} diperbarui</Badge>{' '}
          <Badge tone="neutral">{result.skipped} dilewati</Badge>{' '}
          {result.failed > 0 && <Badge tone="critical">{result.failed} gagal</Badge>}
        </h3>
        <button className="ghost" onClick={onDismiss}>
          Tutup
        </button>
      </div>
      <div style={{ maxHeight: 360, overflow: 'auto' }}>
        <table className="table" style={{ fontSize: '0.85rem' }}>
          <thead>
            <tr>
              <th>Username</th>
              <th>Status</th>
              <th>Keterangan</th>
            </tr>
          </thead>
          <tbody>
            {result.rows.map((r, i) => (
              <tr key={i}>
                <td>{r.username ? <code>{r.username}</code> : <span className="muted">—</span>}</td>
                <td>
                  <Badge tone={STATUS_TONE[r.status]}>{r.status}</Badge>
                </td>
                <td>{r.message ?? <span className="muted">—</span>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
