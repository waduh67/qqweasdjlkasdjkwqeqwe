/**
 * Material satu work order: rencana (BOM), pengeluaran dari gudang, pencatatan pemakaian
 * curah, dan scan nomor seri.
 *
 * Jalur ini sudah lama lengkap di server tapi tak punya layar sama sekali — praktis hanya
 * bisa dipakai lewat curl, jadi rencana material tak pernah diisi dan nasib unit berserial
 * tak pernah dideklarasikan. Akibatnya paling terasa di tombol "Selesai": work order TIDAK
 * bisa ditutup selama masih ada unit berserial yang keluar gudang tapi belum di-scan, dan
 * tanpa layar ini teknisi cuma melihat penolakan berulang tanpa tahu sebabnya. Karena itu
 * `unscannedQuantity` dipajang paling menonjol di sini, bukan disembunyikan di kolom kesekian.
 *
 * Satu kartu, beberapa `<section>` yang di-scroll — BUKAN tab. Alasannya sama dengan
 * `WorkOrderDetailBody`: teknisi mengisi banyak field berturut-turut sambil berdiri di depan
 * pelanggan, dan tab memaksanya mengingat apa yang ada di balik tab lain.
 *
 * Semua nama (`itemName`, `technicianName`, `scannedByName`) datang APA ADANYA dari read
 * model. Tidak ada penggabungan id → nama di klien: `/item-master` dan `/api/users` menuntut
 * `inventory.item.view`/`iam.user.view` yang justru tidak dipegang teknisi lapangan, dan
 * penggabungan semacam itulah yang dulu membuat layar gudang memajang UUID telanjang untuk
 * orang yang paling sering membacanya.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Table, TableBody, TableCell, TableHeader, TableHeaderCell, TableRow, Text } from '@fluentui/react-components'
import { ApiError } from '@/api/client'
import {
  listInventoryLocations,
  listUserDirectory,
  operationEnvelope,
  type LocationView,
  type OperationEnvelope,
} from '@/api/inventory'
import type { User } from '@/api/types'
import type { WorkOrderStatus } from '@/api/workorder'
import {
  clearWorkOrderMaterialPlan,
  getWorkOrderMaterialTemplate,
  issueWorkOrderMaterial,
  listWorkOrderMaterials,
  planWorkOrderMaterial,
  recordWorkOrderMaterialUsage,
  scanWorkOrderMaterialSerial,
  type IssueMaterialLineBody,
  type MaterialSerialOutcome,
  type PlanMaterialLineBody,
  type WorkOrderMaterialTemplateView,
  type WorkOrderMaterialView,
} from '@/api/workorderMaterial'
import { useCan } from '@/auth/useCan'
import { Badge, Button, SelectField, SkeletonRows, TextField, TextareaField } from '@/components/atoms'
import { useConfirm, useToast } from '@/system'
import { fmt } from '@/utils/woLabels'

/** Hanya tiga nasib yang dikenal server — menawarkan yang keempat berarti menjanjikan 400. */
const OUTCOME_LABEL: Record<MaterialSerialOutcome, string> = {
  INSTALLED: 'Terpasang di pelanggan',
  RETURNED: 'Dibawa pulang utuh',
  LOST: 'Hilang / rusak di lapangan',
}

const OUTCOMES: readonly MaterialSerialOutcome[] = ['INSTALLED', 'RETURNED', 'LOST']

const OUTCOME_TONE: Record<MaterialSerialOutcome, 'good' | 'neutral' | 'critical'> = {
  INSTALLED: 'good',
  RETURNED: 'neutral',
  LOST: 'critical',
}

/** Lokasi yang masuk akal sebagai ASAL barang (rak gudang), versus tujuan di kendaraan teknisi. */
const SOURCE_KINDS = new Set(['WAREHOUSE', 'BIN'])
const TECHNICIAN_KINDS = new Set(['VEHICLE', 'TECHNICIAN'])

/**
 * Item yang boleh dipilih di formulir — dirakit dari baris material dan template WO, BUKAN
 * dari `/api/inventory/item-master`. Dua sumber itu sudah membawa nama, kode, satuan, dan
 * penanda berserial, jadi memanggil master item hanya akan menambah satu izin yang tak
 * dipegang teknisi demi data yang sudah ada di tangan.
 */
interface MaterialItemOption {
  readonly itemId: string
  readonly itemCode: string
  readonly itemName: string
  readonly unit: string
  readonly serialized: boolean
}

/**
 * Permintaan koreksi atas satu baris serial yang SUDAH tercatat.
 *
 * Koreksi TIDAK butuh endpoint sendiri: cukup men-scan ulang nomor seri yang sama dengan nasib
 * yang benar. `WorkOrderMaterialLine.attachSerial` di server melakukan UPSERT per aset —
 * `serials.filterNot { it.assetId == serial.assetId } + serial` — lalu SELALU menghitung ulang
 * `usedQuantity`/`returnedQuantity`/`lostQuantity` dari daftar serial hasil penggabungan itu.
 * Jadi scan kedua MENGGANTI scan pertama, bukan menumpuknya, dan angka di tabel material ikut
 * benar dengan sendirinya tanpa satu pun operasi tambahan.
 *
 * Karena itu JANGAN tergoda menambah endpoint "hapus serial" untuk keperluan ini. Id baris
 * serial sengaja dipertahankan saat upsert supaya saga pemotongan saldo tetap idempoten; hapus
 * lalu sisipkan ulang melahirkan id baru, dan saga akan memotong saldo unit yang SAMA dua kali.
 * Yang kurang selama ini cuma di layar: teknisi yang terlanjur memilih nasib yang salah tidak
 * pernah diberi tahu bahwa jalan keluarnya sesederhana menembak ulang unit yang sama.
 */
interface SerialCorrection {
  readonly serialNumber: string
  readonly macAddress: string | null
  /** Nasib yang SEKARANG tercatat — dipajang supaya jelas apa persisnya yang akan diganti. */
  readonly previousOutcome: MaterialSerialOutcome
  readonly itemName: string
}

export function WorkOrderMaterials({ workOrderId, status }: { workOrderId: string; status: WorkOrderStatus }) {
  const { can } = useCan()
  // Tanpa izin baca komponen TIDAK merender apa pun — bukan kartu kosong bertuliskan "akses
  // ditolak", karena halaman detail WO sudah ramai dan kartu semacam itu cuma jadi bising.
  if (!can('workorder.material.view')) return null
  return <MaterialsCard workOrderId={workOrderId} status={status} />
}

function MaterialsCard({ workOrderId, status }: { workOrderId: string; status: WorkOrderStatus }) {
  const { can } = useCan()
  const toast = useToast()
  const canRecord = can('workorder.material.record')
  const canIssue = can('workorder.material.issue')
  // WO yang sudah selesai/batal hanya dibaca: aksi tulisnya ditolak server, dan menawarkan
  // tombol yang pasti ditolak lebih membingungkan daripada tidak menawarkannya sama sekali.
  const terminal = status === 'DONE' || status === 'CANCELLED'

  const [rows, setRows] = useState<readonly WorkOrderMaterialView[]>([])
  const [template, setTemplate] = useState<readonly WorkOrderMaterialTemplateView[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  // Diangkat ke kartu karena pemicunya ada di TABEL serial sementara formulirnya ada di
  // bagian scan — dua tempat yang berjauhan di layar dan tidak saling bersarang.
  const [correction, setCorrection] = useState<SerialCorrection | null>(null)

  const reload = useCallback(async () => {
    try {
      const [nextRows, nextTemplate] = await Promise.all([
        listWorkOrderMaterials(workOrderId),
        // Template ikut izin baca yang sama, tapi kegagalannya tidak boleh mengosongkan
        // tabel: ia hanya memberi usulan angka, bukan data yang dibaca orang.
        getWorkOrderMaterialTemplate(workOrderId).catch(() => [] as WorkOrderMaterialTemplateView[]),
      ])
      setRows(nextRows)
      setTemplate(nextTemplate)
      setError(null)
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'Gagal memuat material work order')
    } finally {
      setLoading(false)
    }
  }, [workOrderId])

  useEffect(() => {
    void reload()
  }, [reload])

  const options = useMemo(() => buildOptions(rows, template), [rows, template])
  const unscannedTotal = rows.reduce((sum, row) => sum + row.unscannedQuantity, 0)
  const scanned = useMemo(
    () => rows.flatMap((row) => row.serials.map((serial) => ({ row, serial }))),
    [rows],
  )

  const writable = !terminal
  const showPlan = canRecord && writable
  const showIssue = canIssue && writable
  const showUsage = canRecord && writable
  const showScan = canRecord && writable

  return (
    <div className="card stack" style={{ gap: '1.1rem' }}>
      <div className="spread" style={{ alignItems: 'baseline', gap: '0.5rem' }}>
        <Text as="h3" size={300} weight="semibold" style={{ margin: 0 }}>Material</Text>
        <Text as="span" className="muted" size={200}>{rows.length} item terencana</Text>
      </div>

      {error && <Text as="p" className="error" size={200} style={{ margin: 0 }} role="alert">{error}</Text>}

      {loading ? (
        <SkeletonRows rows={2} />
      ) : (
        <>
          {/* Peringatan paling atas, sebelum tabel: inilah satu-satunya hal yang menahan
              tombol "Selesai", jadi ia harus terbaca sebelum mata turun ke angka-angka. */}
          {unscannedTotal > 0 && (
            <div className="row wrap" role="alert" style={{ gap: '0.5rem', alignItems: 'center' }}>
              <Badge tone="warning">{unscannedTotal} unit belum di-scan</Badge>
              <Text as="span" size={200}>
                Work order tidak bisa diselesaikan sampai nasib setiap unit berserial yang keluar gudang
                dideklarasikan lewat scan nomor seri.
              </Text>
            </div>
          )}

          <MaterialTable rows={rows} />

          {scanned.length > 0 && (
            <section className="stack" style={{ gap: '0.35rem' }}>
              <Text as="h4" size={200} weight="semibold" style={{ margin: 0 }}>Nomor seri yang sudah di-scan</Text>
              <div className="table-wrap">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHeaderCell>Nomor seri</TableHeaderCell>
                      <TableHeaderCell>Item</TableHeaderCell>
                      <TableHeaderCell>Nasib</TableHeaderCell>
                      <TableHeaderCell>Waktu</TableHeaderCell>
                      <TableHeaderCell>Oleh</TableHeaderCell>
                      {/* Kolom aksi ikut gerbang yang SAMA dengan bagian scan: koreksi tak lain
                          adalah scan ulang, jadi siapa yang boleh mengoreksi = siapa yang boleh
                          men-scan. Dua gerbang terpisah cepat atau lambat akan berbeda isi. */}
                      {showScan && <TableHeaderCell>Aksi</TableHeaderCell>}
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {scanned.map(({ row, serial }) => (
                      <TableRow key={serial.id}>
                        <TableCell>
                          <span className="stack" style={{ gap: 0 }}>
                            <Text as="strong" size={200}>{serial.serialNumber}</Text>
                            {serial.macAddress && (
                              <Text as="span" className="muted" size={100}>{serial.macAddress}</Text>
                            )}
                          </span>
                        </TableCell>
                        <TableCell>{row.itemName}</TableCell>
                        <TableCell><Badge tone={OUTCOME_TONE[serial.outcome]}>{OUTCOME_LABEL[serial.outcome]}</Badge></TableCell>
                        <TableCell className="muted">{fmt(serial.scannedAt)}</TableCell>
                        {/* Nama dari read model apa adanya — jangan diambil ulang dari `/api/users`. */}
                        <TableCell className="muted">{serial.scannedByName}</TableCell>
                        {showScan && (
                          <TableCell>
                            <Button
                              variant="subtle"
                              aria-label={`Koreksi ${serial.serialNumber}`}
                              onClick={() =>
                                setCorrection({
                                  serialNumber: serial.serialNumber,
                                  macAddress: serial.macAddress,
                                  previousOutcome: serial.outcome,
                                  itemName: row.itemName,
                                })
                              }
                            >
                              Koreksi
                            </Button>
                          </TableCell>
                        )}
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
            </section>
          )}

          {terminal && (
            <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
              Work order sudah {status === 'DONE' ? 'selesai' : 'dibatalkan'} — material hanya bisa dibaca.
            </Text>
          )}

          {showPlan && <PlanSection workOrderId={workOrderId} rows={rows} template={template} onSaved={reload} toast={toast} />}
          {showIssue && <IssueSection workOrderId={workOrderId} options={options} onSaved={reload} toast={toast} />}
          {showUsage && <UsageSection workOrderId={workOrderId} rows={rows} onSaved={reload} toast={toast} />}
          {showScan && (
            <ScanSection
              workOrderId={workOrderId}
              correction={correction}
              onCorrectionEnd={() => setCorrection(null)}
              onSaved={reload}
              toast={toast}
            />
          )}
        </>
      )}
    </div>
  )
}

// ————————————————————————————— tabel —————————————————————————————

function MaterialTable({ rows }: { rows: readonly WorkOrderMaterialView[] }) {
  if (rows.length === 0) {
    return <Text as="p" className="muted" size={200} style={{ margin: 0 }}>Belum ada material yang direncanakan.</Text>
  }
  return (
    <div className="table-wrap">
      <Table aria-label="Material work order">
        <TableHeader>
          <TableRow>
            <TableHeaderCell>Item</TableHeaderCell>
            <TableHeaderCell>Satuan</TableHeaderCell>
            <TableHeaderCell>Rencana</TableHeaderCell>
            <TableHeaderCell>Dikeluarkan</TableHeaderCell>
            <TableHeaderCell>Terpakai</TableHeaderCell>
            <TableHeaderCell>Dikembalikan</TableHeaderCell>
            <TableHeaderCell>Hilang</TableHeaderCell>
            <TableHeaderCell>Belum di-scan</TableHeaderCell>
            <TableHeaderCell>Teknisi</TableHeaderCell>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((row) => (
            <TableRow key={row.id}>
              <TableCell>
                <span className="stack" style={{ gap: 0 }}>
                  {/* `itemName` & `itemCode` dari read model — tidak ada lookup ke master item. */}
                  <Text as="strong" size={200}>{row.itemName}</Text>
                  <Text as="span" className="muted" size={100}>{row.itemCode}</Text>
                </span>
              </TableCell>
              <TableCell className="muted">{row.unit}</TableCell>
              <TableCell className="tnum">{row.plannedQuantity}</TableCell>
              <TableCell className="tnum">{row.issuedQuantity}</TableCell>
              <TableCell className="tnum">{row.usedQuantity}</TableCell>
              <TableCell className="tnum">{row.returnedQuantity}</TableCell>
              <TableCell className="tnum">{row.lostQuantity}</TableCell>
              <TableCell className="tnum">
                {/* Nol ditulis polos; di atas nol WAJIB menonjol — angka inilah yang menahan
                    penyelesaian WO, dan angka yang tenggelam di kolom kedelapan tak terbaca. */}
                {row.unscannedQuantity > 0 ? (
                  <Badge tone="warning">{row.unscannedQuantity} belum di-scan</Badge>
                ) : (
                  <span className="muted">0</span>
                )}
              </TableCell>
              <TableCell className="muted">{row.technicianName ?? '—'}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

// ————————————————————————————— rencana (BOM) —————————————————————————————

function PlanSection({
  workOrderId,
  rows,
  template,
  onSaved,
  toast,
}: {
  workOrderId: string
  rows: readonly WorkOrderMaterialView[]
  template: readonly WorkOrderMaterialTemplateView[]
  onSaved: () => Promise<void>
  toast: Toaster
}) {
  const confirm = useConfirm()
  const [quantities, setQuantities] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)

  // Angka yang sudah direncanakan menang atas angka template: itulah yang sedang berlaku.
  const plannedById = useMemo(
    () => new Map(rows.map((row) => [row.itemId, row.plannedQuantity])),
    [rows],
  )
  const valueOf = (line: WorkOrderMaterialTemplateView) =>
    quantities[line.itemId] ?? String(plannedById.get(line.itemId) ?? line.plannedQuantity)

  const save = async (lines: readonly PlanMaterialLineBody[], message: string) => {
    setBusy(true)
    try {
      await planWorkOrderMaterial(workOrderId, lines)
      setQuantities({})
      await onSaved()
      toast.success(message)
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.message : 'Rencana material tidak dapat disimpan')
    } finally {
      setBusy(false)
    }
  }

  const saveEdited = () =>
    save(
      template
        .map((line) => ({ itemId: line.itemId, quantity: Number(valueOf(line)) || 0 }))
        .filter((line) => line.quantity > 0),
      'Rencana material disimpan',
    )

  // `lines: []` BUKAN "kosongkan rencana": server membacanya sebagai "pakai BOM apa adanya"
  // lalu mengisi rencana dari template WO. Inilah jalur pra-isi yang dipakai dispatcher saat
  // membuka WO baru.
  const applyTemplateAsIs = () => save([], 'Rencana diisi dari BOM')

  /**
   * Mengosongkan rencana punya FUNGSI SENDIRI (`clearWorkOrderMaterialPlan`, yang menambahkan
   * `clear: true`), bukan sekadar mengirim `lines: []` lewat `planWorkOrderMaterial`.
   *
   * Sebabnya: di kontrak ini `[]` berarti "pakai BOM apa adanya". Dispatcher yang menghapus
   * baris terakhir dari layar lalu menekan simpan akan mengirim daftar kosong dan justru
   * mendapat rencana PENUH kembali dari template — kebalikan persis dari yang ia maksud, dan
   * tanpa satu pun pesan kesalahan yang memberitahunya. Dua maksud yang berlawanan tidak boleh
   * memakai bentuk permintaan yang sama, jadi pengosongan dinyatakan eksplisit.
   *
   * Konfirmasi wajib karena aksinya membuang pekerjaan menyusun rencana yang tidak bisa
   * dibatalkan dari layar ini. Penolakan server (ada baris yang sudah keluar gudang) dipajang
   * APA ADANYA: pesannya sudah ditulis untuk dibaca orangnya, bukan untuk diringkas klien.
   */
  const clearPlan = async () => {
    const approved = await confirm({
      title: 'Kosongkan rencana material',
      message:
        `Seluruh ${rows.length} baris rencana material work order ini dibuang. Baris yang sudah ` +
        'keluar gudang tidak ikut terbuang — server akan menolak permintaannya.',
      confirmLabel: 'Kosongkan',
      danger: true,
    })
    if (!approved) return
    setBusy(true)
    try {
      await clearWorkOrderMaterialPlan(workOrderId)
      setQuantities({})
      await onSaved()
      toast.success('Rencana material dikosongkan')
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.message : 'Rencana material tidak dapat dikosongkan')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="stack" style={{ gap: '0.5rem' }}>
      <Text as="h4" size={200} weight="semibold" style={{ margin: 0 }}>Rencana material</Text>
      {template.length === 0 ? (
        <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
          Tipe work order ini belum punya template BOM.
        </Text>
      ) : (
        <div className="stack" style={{ gap: '0.4rem' }}>
          {template.map((line) => (
            <div className="row wrap" key={line.itemId} style={{ gap: '0.6rem', alignItems: 'flex-end' }}>
              <div className="stack" style={{ gap: 0, flex: 1, minWidth: 160 }}>
                <Text as="strong" size={200}>{line.itemName}</Text>
                <Text as="span" className="muted" size={100}>
                  {line.itemCode} · BOM {line.plannedQuantity} {line.unit}
                  {line.serialized ? ' · berserial' : ''}
                </Text>
                {line.note && <Text as="span" className="muted" size={100}>{line.note}</Text>}
              </div>
              <TextField
                label={`Rencana ${line.itemName}`}
                type="number"
                min={0}
                value={valueOf(line)}
                onChange={(_, data) => setQuantities((prev) => ({ ...prev, [line.itemId]: data.value }))}
                style={{ width: 120 }}
              />
            </div>
          ))}
        </div>
      )}
      {/* Tombol dirakit di luar cabang template supaya "Kosongkan rencana" tetap ada saat tipe
          WO kehilangan template BOM-nya: rencananya sudah telanjur tersusun dan justru itulah
          keadaan yang paling butuh dibersihkan. */}
      {(template.length > 0 || rows.length > 0) && (
        <div className="row wrap" style={{ gap: '0.5rem' }}>
          {template.length > 0 && (
            <>
              <Button variant="primary" disabled={busy} onClick={() => void saveEdited()}>
                {busy ? 'Menyimpan…' : 'Simpan rencana'}
              </Button>
              <Button disabled={busy} onClick={() => void applyTemplateAsIs()}>Pakai BOM apa adanya</Button>
            </>
          )}
          {rows.length > 0 && (
            <Button variant="danger" disabled={busy} onClick={() => void clearPlan()}>Kosongkan rencana</Button>
          )}
        </div>
      )}
    </section>
  )
}

// ————————————————————————————— pengeluaran dari gudang —————————————————————————————

interface IssueLineDraft {
  readonly key: string
  itemId: string
  quantity: string
  /** Satu SN per baris. Untuk item berserial inilah yang menentukan kuantitas. */
  serials: string
}

function emptyIssueLine(): IssueLineDraft {
  return { key: `line-${Math.random().toString(36).slice(2, 9)}`, itemId: '', quantity: '1', serials: '' }
}

function IssueSection({
  workOrderId,
  options,
  onSaved,
  toast,
}: {
  workOrderId: string
  options: readonly MaterialItemOption[]
  onSaved: () => Promise<void>
  toast: Toaster
}) {
  const directory = useWarehouseDirectory()
  const envelopeFor = useIdempotentEnvelope()
  const [fromLocationId, setFromLocationId] = useState('')
  const [custodianId, setCustodianId] = useState('')
  const [technicianId, setTechnicianId] = useState('')
  const [technicianLocationId, setTechnicianLocationId] = useState('')
  const [reason, setReason] = useState('')
  const [lines, setLines] = useState<IssueLineDraft[]>([emptyIssueLine()])
  const [busy, setBusy] = useState(false)

  const optionById = useMemo(() => new Map(options.map((entry) => [entry.itemId, entry])), [options])
  const sourceLocations = directory.locations.filter((entry) => SOURCE_KINDS.has(entry.kind))
  const technicianLocations = directory.locations.filter((entry) => TECHNICIAN_KINDS.has(entry.kind))

  const payloadLines = useMemo<IssueMaterialLineBody[]>(
    () => lines.map((line) => toIssueLine(line, optionById.get(line.itemId))),
    [lines, optionById],
  )

  const problems = useMemo(() => {
    const found: string[] = []
    if (!fromLocationId) found.push('Lokasi gudang asal wajib dipilih.')
    if (!custodianId) found.push('Pemegang custody wajib dipilih.')
    if (!technicianId) found.push('Teknisi wajib dipilih.')
    if (!technicianLocationId) found.push('Lokasi van teknisi wajib dipilih.')
    if (!reason.trim()) found.push('Alasan wajib diisi.')
    payloadLines.forEach((line, index) => {
      const item = optionById.get(line.itemId)
      if (!item) {
        found.push(`Baris ${index + 1}: item belum dipilih.`)
        return
      }
      if (line.quantity <= 0) {
        found.push(
          item.serialized
            ? `Baris ${index + 1}: masukkan minimal satu nomor seri.`
            : `Baris ${index + 1}: jumlah harus lebih dari nol.`,
        )
      }
      const duplicate = findDuplicate(line.serialNumbers ?? [])
      if (duplicate) found.push(`Baris ${index + 1}: nomor seri ${duplicate} ditulis dua kali.`)
    })
    const duplicateItem = findDuplicate(payloadLines.map((line) => line.itemId).filter(Boolean))
    if (duplicateItem) found.push('Satu item hanya boleh muncul di satu baris.')
    return found
  }, [fromLocationId, custodianId, technicianId, technicianLocationId, reason, payloadLines, optionById])

  const submit = async () => {
    setBusy(true)
    try {
      const core = {
        fromLocationId,
        custodianId,
        technicianId,
        technicianLocationId,
        lines: payloadLines,
        reason: reason.trim(),
      }
      const envelope = await envelopeFor(core)
      await issueWorkOrderMaterial(workOrderId, { ...core, ...envelope })
      // Kunci dibuang HANYA setelah server menjawab: sampai saat itu percobaan ulang harus
      // memakai kunci yang sama supaya klik kedua tidak jadi pengeluaran barang kedua.
      envelopeFor.reset()
      setLines([emptyIssueLine()])
      setReason('')
      await onSaved()
      toast.success('Material dikeluarkan dari gudang')
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.message : 'Pengeluaran material tidak dapat disimpan')
    } finally {
      setBusy(false)
    }
  }

  const update = (index: number, patch: Partial<IssueLineDraft>) =>
    setLines((prev) => prev.map((line, position) => (position === index ? { ...line, ...patch } : line)))

  return (
    <section className="stack" style={{ gap: '0.5rem' }}>
      <Text as="h4" size={200} weight="semibold" style={{ margin: 0 }}>Keluarkan dari gudang</Text>

      {directory.denied && (
        <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
          Daftar lokasi dan pengguna tidak bisa dimuat — formulir pengeluaran butuh izin gudang
          (<code>inventory.location.view</code> dan <code>iam.user.view</code>). Data material di atas
          tetap terbaca.
        </Text>
      )}

      <div className="row wrap" style={{ alignItems: 'flex-end' }}>
        <SelectField label="Lokasi gudang asal" value={fromLocationId} onChange={(_, data) => setFromLocationId(data.value)}>
          <option value="">Pilih lokasi…</option>
          {sourceLocations.map((entry) => (
            <option key={entry.id} value={entry.id}>{entry.code}</option>
          ))}
        </SelectField>
        <SelectField label="Pemegang custody" value={custodianId} onChange={(_, data) => setCustodianId(data.value)}>
          <option value="">Pilih pemegang…</option>
          {directory.users.map((entry) => (
            <option key={entry.id} value={entry.id}>{entry.name}</option>
          ))}
        </SelectField>
        <SelectField label="Teknisi" value={technicianId} onChange={(_, data) => setTechnicianId(data.value)}>
          <option value="">Pilih teknisi…</option>
          {directory.users.map((entry) => (
            <option key={entry.id} value={entry.id}>{entry.name}</option>
          ))}
        </SelectField>
        <SelectField
          label="Lokasi van teknisi"
          value={technicianLocationId}
          onChange={(_, data) => setTechnicianLocationId(data.value)}
        >
          <option value="">Pilih van…</option>
          {technicianLocations.map((entry) => (
            <option key={entry.id} value={entry.id}>{entry.code}</option>
          ))}
        </SelectField>
      </div>

      <div className="stack" style={{ gap: '0.6rem' }}>
        {lines.map((line, index) => {
          const item = optionById.get(line.itemId)
          const serialCount = countSerials(line.serials)
          return (
            <div className="stack" key={line.key} style={{ gap: '0.4rem' }}>
              <div className="row wrap" style={{ alignItems: 'flex-end' }}>
                <SelectField
                  label={`Item baris ${index + 1}`}
                  value={line.itemId}
                  onChange={(_, data) => update(index, { itemId: data.value, serials: '' })}
                >
                  <option value="">Pilih item…</option>
                  {options.map((entry) => (
                    <option key={entry.itemId} value={entry.itemId}>{entry.itemName} ({entry.itemCode})</option>
                  ))}
                </SelectField>
                {item?.serialized ? (
                  // Kuantitas item berserial DITURUNKAN dari cacah SN, tidak diketik terpisah:
                  // dua kolom yang bisa berbeda hanya melahirkan penolakan "quantity mismatch"
                  // yang tak menunjuk kolom mana yang salah.
                  <div className="stack" style={{ gap: 0 }}>
                    <Text as="span" className="muted" size={200}>Jumlah</Text>
                    <Text as="strong" className="tnum">{serialCount}</Text>
                  </div>
                ) : (
                  <TextField
                    label={`Jumlah baris ${index + 1}`}
                    value={line.quantity}
                    onChange={(_, data) => update(index, { quantity: data.value })}
                  />
                )}
                {lines.length > 1 && (
                  <Button
                    variant="danger"
                    onClick={() => setLines((prev) => prev.filter((_, position) => position !== index))}
                  >
                    Hapus baris
                  </Button>
                )}
              </div>
              {item?.serialized && (
                <TextareaField
                  label={`Nomor seri baris ${index + 1}`}
                  rows={3}
                  value={line.serials}
                  hint="Satu SN per baris. Tempel langsung dari hasil scan."
                  onChange={(_, data) => update(index, { serials: data.value })}
                />
              )}
            </div>
          )
        })}
        <div>
          <Button onClick={() => setLines((prev) => [...prev, emptyIssueLine()])}>Tambah baris</Button>
        </div>
      </div>

      <TextareaField label="Alasan" required rows={2} value={reason} onChange={(_, data) => setReason(data.value)} />

      {problems.length > 0 && (
        <div className="stack" style={{ gap: '0.15rem' }} role="alert">
          {problems.map((problem) => (
            <Text as="span" className="error" size={200} key={problem}>{problem}</Text>
          ))}
        </div>
      )}

      <div>
        <Button variant="primary" disabled={busy || problems.length > 0} onClick={() => void submit()}>
          {busy ? 'Mengeluarkan…' : 'Keluarkan material'}
        </Button>
      </div>
    </section>
  )
}

// ————————————————————————————— pemakaian curah —————————————————————————————

function UsageSection({
  workOrderId,
  rows,
  onSaved,
  toast,
}: {
  workOrderId: string
  rows: readonly WorkOrderMaterialView[]
  onSaved: () => Promise<void>
  toast: Toaster
}) {
  const [itemId, setItemId] = useState('')
  const [used, setUsed] = useState('')
  const [returned, setReturned] = useState('')
  const [lost, setLost] = useState('')
  const [varianceReason, setVarianceReason] = useState('')
  const [busy, setBusy] = useState(false)

  const selected = rows.find((row) => row.itemId === itemId)

  const problems: string[] = []
  if (!itemId) problems.push('Pilih item lebih dulu.')
  // Server juga menolak ini, tapi pesan dari klien sampai lebih cepat ke teknisi yang sedang
  // berdiri di lapangan — dan menyebut jalan keluarnya, bukan sekadar "400 Bad Request".
  if (selected?.serialized) {
    problems.push(
      `${selected.itemName} adalah barang berserial: jumlah terpakainya datang dari scan nomor seri, bukan dari formulir pemakaian curah.`,
    )
  }
  const usedQuantity = Number(used)
  if (used.trim() === '' || !Number.isFinite(usedQuantity) || usedQuantity < 0) {
    problems.push('Jumlah terpakai wajib diisi angka tak negatif.')
  }

  const submit = async () => {
    setBusy(true)
    try {
      await recordWorkOrderMaterialUsage(workOrderId, {
        itemId,
        usedQuantity,
        returnedQuantity: optionalQuantity(returned),
        lostQuantity: optionalQuantity(lost),
        varianceReason: varianceReason.trim() || null,
      })
      setUsed('')
      setReturned('')
      setLost('')
      setVarianceReason('')
      await onSaved()
      toast.success('Pemakaian material dicatat')
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.message : 'Pemakaian material tidak dapat dicatat')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="stack" style={{ gap: '0.5rem' }}>
      <Text as="h4" size={200} weight="semibold" style={{ margin: 0 }}>Catat pemakaian curah</Text>
      {rows.length === 0 ? (
        <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
          Belum ada material untuk dicatat pemakaiannya.
        </Text>
      ) : (
        <>
          <div className="row wrap" style={{ alignItems: 'flex-end' }}>
            <SelectField label="Item" value={itemId} onChange={(_, data) => setItemId(data.value)}>
              <option value="">Pilih item…</option>
              {rows.map((row) => (
                <option key={row.itemId} value={row.itemId}>{row.itemName} ({row.itemCode})</option>
              ))}
            </SelectField>
            <TextField
              label="Terpakai"
              type="number"
              min={0}
              value={used}
              onChange={(_, data) => setUsed(data.value)}
              style={{ width: 120 }}
            />
            <TextField
              label="Dikembalikan"
              type="number"
              min={0}
              value={returned}
              onChange={(_, data) => setReturned(data.value)}
              style={{ width: 120 }}
            />
            <TextField
              label="Hilang"
              type="number"
              min={0}
              value={lost}
              onChange={(_, data) => setLost(data.value)}
              style={{ width: 120 }}
            />
          </div>
          <TextField
            label="Alasan selisih (opsional)"
            value={varianceReason}
            onChange={(_, data) => setVarianceReason(data.value)}
            placeholder="mis. dropcore terpotong kependekan"
          />
          {problems.length > 0 && (
            <div className="stack" style={{ gap: '0.15rem' }} role="alert">
              {problems.map((problem) => (
                <Text as="span" className="error" size={200} key={problem}>{problem}</Text>
              ))}
            </div>
          )}
          <div>
            <Button variant="primary" disabled={busy || problems.length > 0} onClick={() => void submit()}>
              {busy ? 'Menyimpan…' : 'Catat pemakaian'}
            </Button>
          </div>
        </>
      )}
    </section>
  )
}

// ————————————————————————————— scan nomor seri —————————————————————————————

function ScanSection({
  workOrderId,
  correction,
  onCorrectionEnd,
  onSaved,
  toast,
}: {
  workOrderId: string
  correction: SerialCorrection | null
  onCorrectionEnd: () => void
  onSaved: () => Promise<void>
  toast: Toaster
}) {
  const [serialNumber, setSerialNumber] = useState('')
  const [outcome, setOutcome] = useState<MaterialSerialOutcome>('INSTALLED')
  const [macAddress, setMacAddress] = useState('')
  const [busy, setBusy] = useState(false)
  const serialRef = useRef<HTMLInputElement>(null)
  const outcomeRef = useRef<HTMLSelectElement>(null)

  useEffect(() => {
    if (!correction) return
    setSerialNumber(correction.serialNumber)
    setOutcome(correction.previousOutcome)
    setMacAddress(correction.macAddress ?? '')
    // Fokus ke pemilih NASIB, bukan ke kolom serial. Nomor serinya sudah benar — yang salah
    // justru nasibnya, dan itulah satu-satunya hal yang datang untuk diubah.
    outcomeRef.current?.focus()
  }, [correction])

  const cancelCorrection = () => {
    setSerialNumber('')
    setMacAddress('')
    setOutcome('INSTALLED')
    onCorrectionEnd()
    serialRef.current?.focus()
  }

  const submit = async () => {
    const trimmed = serialNumber.trim()
    if (!trimmed || busy) return
    setBusy(true)
    try {
      // Koreksi memakai endpoint yang SAMA dengan scan biasa: server meng-upsert per aset dan
      // menghitung ulang kuantitas dari daftar serial, jadi scan ulang dengan nasib berbeda
      // memperbaiki catatan lama alih-alih menambah catatan kedua. Lihat [SerialCorrection].
      await scanWorkOrderMaterialSerial(workOrderId, {
        serialNumber: trimmed,
        outcome,
        macAddress: macAddress.trim() || null,
      })
      const corrected = correction
      setSerialNumber('')
      setMacAddress('')
      onCorrectionEnd()
      // Fokus dikembalikan ke kolom serial: pemindai barcode menembakkan unit berikutnya
      // langsung setelah bunyi bip, dan kolom yang kehilangan fokus membuang tembakan itu.
      serialRef.current?.focus()
      await onSaved()
      toast.success(
        corrected
          ? `${trimmed} dikoreksi menjadi ${OUTCOME_LABEL[outcome].toLowerCase()}`
          : `${trimmed} dicatat sebagai ${OUTCOME_LABEL[outcome].toLowerCase()}`,
      )
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.message : 'Nomor seri tidak dapat dicatat')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="stack" style={{ gap: '0.5rem' }}>
      <Text as="h4" size={200} weight="semibold" style={{ margin: 0 }}>
        {correction ? 'Koreksi scan nomor seri' : 'Scan nomor seri'}
      </Text>
      {correction && (
        // Mode koreksi HARUS terbaca sebelum tombol simpan ditekan: tanpa kalimat ini teknisi
        // mengira ia sedang menambah baris kedua untuk unit yang sama dan malah membatalkannya.
        <div className="row wrap" role="status" style={{ gap: '0.5rem', alignItems: 'center' }}>
          <Badge tone="warning">Mode koreksi</Badge>
          <Text as="span" size={200}>
            Scan ini akan MENGGANTI catatan {correction.serialNumber} ({correction.itemName}) yang
            sekarang berbunyi “{OUTCOME_LABEL[correction.previousOutcome]}” — bukan menambah baris baru.
          </Text>
          <Button variant="subtle" onClick={cancelCorrection}>Batalkan koreksi</Button>
        </div>
      )}
      <div className="row wrap" style={{ alignItems: 'flex-end' }}>
        <TextField
          label="Nomor seri"
          value={serialNumber}
          input={{ ref: serialRef }}
          // Pemindai barcode mengetik isinya lalu menekan Enter. Tanpa penanganan Enter,
          // tiap unit menuntut satu klik tombol — di tangan yang juga memegang tangga.
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault()
              void submit()
            }
          }}
          onChange={(_, data) => setSerialNumber(data.value)}
          placeholder="Tembak dengan pemindai atau ketik"
          style={{ flex: 1, minWidth: 180 }}
        />
        <SelectField
          label="Nasib unit"
          value={outcome}
          select={{ ref: outcomeRef }}
          onChange={(_, data) => setOutcome(data.value as MaterialSerialOutcome)}
        >
          {OUTCOMES.map((entry) => (
            <option key={entry} value={entry}>{OUTCOME_LABEL[entry]}</option>
          ))}
        </SelectField>
        <TextField
          label="MAC address (opsional)"
          value={macAddress}
          onChange={(_, data) => setMacAddress(data.value)}
          style={{ minWidth: 160 }}
        />
        <Button variant="primary" disabled={busy || !serialNumber.trim()} onClick={() => void submit()}>
          {busy ? 'Menyimpan…' : correction ? 'Simpan koreksi' : 'Simpan scan'}
        </Button>
      </div>
      <Text as="span" className="muted" size={100}>
        Setiap unit berserial yang keluar gudang harus dinyatakan nasibnya — hanya yang terpasang
        di pelanggan yang memotong saldo saat work order disetujui. Salah pilih nasib bukan jalan
        buntu: tembak ulang unit yang sama lewat tombol “Koreksi” di tabel di atas.
      </Text>
    </section>
  )
}

// ————————————————————————————— kakas —————————————————————————————

/** Bentuk minimum `useToast()` yang dipakai di berkas ini. */
interface Toaster {
  success: (message: string) => void
  error: (message: string) => void
}

/**
 * Lokasi & direktori pengguna untuk pemilih di formulir pengeluaran.
 *
 * Keduanya menuntut izin gudang (`inventory.location.view`, `iam.user.view`) yang sering TIDAK
 * dipegang orang yang membuka work order. Kegagalannya karena itu DITELAN: 403 di sini hanya
 * boleh mengosongkan dua pemilih, bukan ikut mengosongkan tabel material yang namanya sudah
 * lengkap dari read model.
 */
function useWarehouseDirectory() {
  const [locations, setLocations] = useState<readonly LocationView[]>([])
  const [users, setUsers] = useState<readonly User[]>([])
  const [denied, setDenied] = useState(false)

  useEffect(() => {
    let alive = true
    void (async () => {
      const [location, user] = await Promise.allSettled([listInventoryLocations(), listUserDirectory()])
      if (!alive) return
      setLocations(location.status === 'fulfilled' ? location.value : [])
      setUsers(user.status === 'fulfilled' ? user.value : [])
      setDenied(location.status === 'rejected' || user.status === 'rejected')
    })()
    return () => {
      alive = false
    }
  }, [])

  return { locations, users, denied }
}

/**
 * Kunci operasi yang BERTAHAN selama isian tidak berubah.
 *
 * Petugas gudang bekerja di jaringan putus-nyambung dan akan menekan "Keluarkan" dua kali
 * setiap kali layarnya diam. Kalau percobaan kedua memakai kunci baru — padahal request
 * pertama sudah sampai — barangnya keluar DUA KALI, dan selisihnya baru ketahuan saat stok
 * fisik diadu. Kalau kuncinya malah abadi, pengeluaran berikutnya yang isinya berbeda ditolak
 * sebagai duplikat. Jadi kunci diikat pada SIDIK PAYLOAD: isian sama persis = percobaan ulang,
 * isian berubah = operasi baru.
 *
 * Disalin dari pola di `WarehouseMovementPanel` dengan sengaja: berkas itu milik layar gudang
 * dan tidak diekspor ke luar. Kalau pola ini kelak perlu tiga pemakai, barulah ia pantas naik
 * jadi kakas bersama.
 */
function useIdempotentEnvelope() {
  const attempt = useRef<{ hash: string; key: string } | null>(null)
  const envelopeFor = useCallback(async (payload: unknown): Promise<OperationEnvelope> => {
    const fresh = await operationEnvelope(payload)
    if (attempt.current?.hash === fresh.payloadHash) {
      return { operationKey: attempt.current.key, payloadHash: fresh.payloadHash }
    }
    attempt.current = { hash: fresh.payloadHash, key: fresh.operationKey }
    return fresh
  }, [])
  const reset = useCallback(() => {
    attempt.current = null
  }, [])
  // Dimemo supaya `reset` tidak dirakit ulang — dan ditempelkan ulang ke fungsi yang sama —
  // pada SETIAP render. Keduanya stabil, jadi penggabungan ini hanya terjadi sekali.
  return useMemo(() => Object.assign(envelopeFor, { reset }), [envelopeFor, reset])
}

function buildOptions(
  rows: readonly WorkOrderMaterialView[],
  template: readonly WorkOrderMaterialTemplateView[],
): readonly MaterialItemOption[] {
  const merged = new Map<string, MaterialItemOption>()
  for (const line of template) {
    merged.set(line.itemId, {
      itemId: line.itemId,
      itemCode: line.itemCode,
      itemName: line.itemName,
      unit: line.unit,
      serialized: line.serialized,
    })
  }
  // Baris material menang: ia menggambarkan item yang BENAR-BENAR ada di WO ini.
  for (const row of rows) {
    merged.set(row.itemId, {
      itemId: row.itemId,
      itemCode: row.itemCode,
      itemName: row.itemName,
      unit: row.unit,
      serialized: row.serialized,
    })
  }
  return [...merged.values()]
}

function countSerials(raw: string): number {
  return splitSerials(raw).length
}

function splitSerials(raw: string): string[] {
  return raw.split('\n').map((entry) => entry.trim()).filter(Boolean)
}

function toIssueLine(line: IssueLineDraft, item: MaterialItemOption | undefined): IssueMaterialLineBody {
  const serialNumbers = item?.serialized ? splitSerials(line.serials) : []
  return {
    itemId: line.itemId,
    quantity: item?.serialized ? serialNumbers.length : Number(line.quantity) || 0,
    serialNumbers,
  }
}

/**
 * Kembar dikenali TANPA peduli besar-kecil huruf: pemindai menulis `SN-001` sementara tangan
 * mengetik `sn-001` untuk unit FISIK yang sama, dan pembandingan sensitif huruf meloloskan
 * keduanya sebagai dua unit.
 */
function findDuplicate(values: readonly string[]): string | null {
  const seen = new Set<string>()
  for (const value of values) {
    const normalized = value.toUpperCase()
    if (seen.has(normalized)) return value
    seen.add(normalized)
  }
  return null
}

/** Kolom kosong berarti "tidak dilaporkan", bukan nol — biarkan server memakai bawaannya. */
function optionalQuantity(raw: string): number | undefined {
  const trimmed = raw.trim()
  if (!trimmed) return undefined
  const value = Number(trimmed)
  return Number.isFinite(value) ? value : undefined
}
