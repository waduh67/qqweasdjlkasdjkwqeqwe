/**
 * Penarikan aset saat work order DISMANTLE — ONT yang dicabut teknisi dari rumah pelanggan.
 *
 * Unit yang dicabut berstatus `CONSUMED` ("terpasang di pelanggan, sudah keluar dari saldo").
 * Scan di sini hanya MENCATAT bahwa unitnya ditarik; saldonya baru bergerak saat work order
 * disetujui orang lain, dan mendaratnya di van teknisi sebagai `RETURNED` — BUKAN langsung jadi
 * stok layak jual. Dari van, unitnya naik ke rak lewat retur gudang biasa.
 *
 * Tanpa layar ini jalur tersebut cuma bisa dipakai lewat curl: teknisi di lapangan tidak punya
 * cara mencatat ONT yang ia cabut, dan unitnya dibawa pulang tanpa jejak apa pun di pembukuan.
 *
 * Baris penarikannya SUDAH bernama lengkap dari read model (`itemName`, `technicianName`,
 * `technicianLocationCode`, `recoveredByName`, `cancelledByName`). JANGAN menggabungkan ulang
 * `itemId`/`technicianId`/`recoveredBy`/`technicianLocationId` ke `/item-master`, `/api/users`,
 * atau `/api/inventory/locations`: endpoint-endpoint itu menuntut `inventory.item.view`/
 * `iam.user.view`/`inventory.location.view` yang justru TIDAK dipegang teknisi lapangan, dan
 * penggabungan semacam itulah yang dulu membuat layar gudang memajang UUID telanjang.
 *
 * Pemilih van pun TIDAK lagi menembak `/api/inventory/locations`: daftarnya datang dari
 * `listWorkOrderVanLocations`, endpoint sempit di bawah namespace work order yang dijaga izin
 * yang memang dipegang teknisi. Jangan kembalikan yang lama — ada tesnya.
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { Table, TableBody, TableCell, TableHeader, TableHeaderCell, TableRow, Text } from '@fluentui/react-components'
import { ApiError } from '@/api/client'
import type { WorkOrderAssigneeView, WorkOrderStatus, WorkOrderType } from '@/api/workorder'
import {
  cancelRecoveredAsset,
  listRecoveredAssets,
  listWorkOrderVanLocations,
  recoverWorkOrderAsset,
  type RecoveredAssetCondition,
  type WorkOrderRecoveredAssetView,
  type WorkOrderVanLocationView,
} from '@/api/workorderMaterial'
import { useCan } from '@/auth/useCan'
import { Badge, Button, SelectField, TextField } from '@/components/atoms'
import { usePrompt, useToast } from '@/system'
import { fmt } from '@/utils/woLabels'

const CONDITION_LABEL: Record<RecoveredAssetCondition, string> = {
  GOOD: 'Layak pakai',
  DAMAGED: 'Rusak',
}

const CONDITIONS: readonly RecoveredAssetCondition[] = ['GOOD', 'DAMAGED']

/**
 * Daftar vannya dijaga izin yang MEMANG dipegang pencatat penarikan, jadi kegagalan di sini
 * bukan lagi "kamu kurang izin melihat master lokasi" — menyuruh orang meminta
 * `inventory.location.view` sekarang salah alamat dan mengirimnya mengejar izin yang bukan
 * jawabannya. Yang tersisa: peran yang dipakai memang tak lengkap, atau servernya bermasalah.
 * Keduanya disebut, karena dari sisi layar keduanya tak bisa dibedakan.
 */
const VAN_LOAD_FAILED =
  'Daftar van teknisi gagal dimuat, jadi tujuan unit yang ditarik belum bisa dipilih. ' +
  'Pemegang izin workorder.material.record semestinya sudah bisa memuatnya, jadi kemungkinannya ' +
  'izin itu belum benar-benar terpasang di peranmu, atau server sedang bermasalah. Muat ulang ' +
  'halaman ini dulu; kalau tetap gagal, laporkan ke administrator. Baris yang sudah tercatat ' +
  'tetap bisa dibaca di bawah.'

export function WorkOrderRecoveredAssets({
  workOrderId,
  type,
  status,
  assignees,
}: {
  workOrderId: string
  type: WorkOrderType
  status: WorkOrderStatus
  assignees: readonly WorkOrderAssigneeView[]
}) {
  const { can } = useCan()
  const toast = useToast()
  const prompt = usePrompt()

  const [rows, setRows] = useState<readonly WorkOrderRecoveredAssetView[]>([])
  const [loading, setLoading] = useState(true)
  const [vans, setVans] = useState<readonly WorkOrderVanLocationView[]>([])
  // Dipisahkan dari `vans.length === 0`: "daftarnya gagal dimuat" dan "belum ada van terdaftar"
  // menuntut tindakan yang berbeda dari pembacanya — yang satu memuat ulang atau mengadu ke
  // administrator, yang satu lagi mendaftarkan van di master data gudang.
  const [vanLoadFailed, setVanLoadFailed] = useState(false)

  const [serialNumber, setSerialNumber] = useState('')
  const [technicianId, setTechnicianId] = useState('')
  const [locationId, setLocationId] = useState('')
  const [condition, setCondition] = useState<RecoveredAssetCondition>('GOOD')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const serialRef = useRef<HTMLInputElement>(null)

  // Aksi tulis dijaga `workorder.material.record`, sama seperti scan serial material.
  // WO batal hanya dibaca: tak ada gunanya mencatat penarikan pada tiket yang tak akan
  // pernah disetujui, dan server pun tidak akan memindahkan saldonya.
  const canRecord = can('workorder.material.record') && status !== 'CANCELLED'
  const canView = can('workorder.material.view')

  const load = useCallback(async () => {
    // Izinnya diperiksa DULU di klien. Tanpa ini setiap pembukaan detail work order —
    // termasuk PSB, perbaikan, dan migrasi yang tak akan pernah punya baris penarikan —
    // menembakkan satu request yang sudah pasti dijawab 403 bagi siapa pun yang tak
    // memegang `workorder.material.view`, mengotori log dengan kegagalan yang bisa
    // diketahui tanpa bertanya ke server.
    if (!canView) {
      setLoading(false)
      return
    }
    try {
      setRows(await listRecoveredAssets(workOrderId))
    } catch {
      // Panel pelengkap: kegagalannya tak boleh menutup detail work order yang sudah tampil.
      setRows([])
    } finally {
      setLoading(false)
    }
  }, [workOrderId, canView])

  useEffect(() => {
    void load()
  }, [load])

  useEffect(() => {
    if (!canRecord) return
    let alive = true
    // Endpoint sempit di bawah namespace work order, BUKAN `/api/inventory/locations`: yang
    // mencabut ONT adalah teknisi yang memegang `workorder.material.record` tapi tidak memegang
    // `inventory.location.view` — dan izin itu akan membuka gudang, bin, serta master data
    // gudang, jauh lebih lebar dari sekadar "van mana yang boleh kupilih".
    listWorkOrderVanLocations(workOrderId)
      .then((found) => {
        if (!alive) return
        setVans(found)
        setVanLoadFailed(false)
      })
      // Kegagalannya DITELAN, bukan dilempar: kartunya tetap utuh dan daftar penarikan tetap
      // terbaca. Yang hilang hanya pemilih van — dan orangnya diberi tahu bahwa daftarnya gagal
      // dimuat, bukan dibiarkan menekan tombol simpan yang diam-diam tak pernah bisa jalan.
      .catch(() => {
        if (!alive) return
        setVans([])
        setVanLoadFailed(true)
      })
    return () => {
      alive = false
    }
  }, [canRecord, workOrderId])

  // Satu teknisi di roster = tak ada yang perlu dipilih. Begitu juga satu van.
  useEffect(() => {
    if (!technicianId && assignees.length === 1) setTechnicianId(assignees[0].id)
  }, [assignees, technicianId])
  useEffect(() => {
    if (!locationId && vans.length === 1) setLocationId(vans[0].id)
  }, [vans, locationId])

  const submit = async () => {
    const serial = serialNumber.trim()
    if (!serial) {
      toast.error('Isi nomor seri unit yang ditarik')
      return
    }
    if (!technicianId) {
      toast.error('Pilih teknisi yang menarik unitnya')
      return
    }
    if (!locationId) {
      toast.error(vanLoadFailed ? VAN_LOAD_FAILED : 'Pilih van teknisi tempat unitnya dibawa')
      return
    }
    setBusy(true)
    try {
      const created = await recoverWorkOrderAsset(workOrderId, {
        serialNumber: serial,
        technicianId,
        technicianLocationId: locationId,
        condition,
        note: note.trim() || null,
      })
      setRows((prev) => [...prev, created])
      setSerialNumber('')
      setNote('')
      toast.success(`${serial} tercatat ditarik`)
    } catch (err) {
      // Pesan server ditampilkan APA ADANYA: penolakan 409 ("bukan unit terpasang",
      // "sudah tercatat ditarik di work order lain") memang sudah ditulis untuk dibaca
      // teknisi, dan menggantinya dengan "gagal menyimpan" membuang satu-satunya petunjuk.
      toast.error(err instanceof ApiError ? err.message : 'Gagal mencatat penarikan unit')
    } finally {
      setBusy(false)
      // Fokus balik ke kolom serial — juga setelah gagal: pemindai barcode menembakkan
      // unit berikutnya tanpa menyentuh layar, dan salah satu unit ditolak bukan alasan
      // teknisi harus mengetuk kolomnya lagi.
      serialRef.current?.focus()
    }
  }

  const cancel = async (row: WorkOrderRecoveredAssetView) => {
    const reason = await prompt({
      title: 'Batalkan catatan penarikan',
      message: `${row.serialNumber} — ${row.itemName}. Barisnya TIDAK dihapus, hanya ditandai dibatalkan beserta alasannya.`,
      label: 'Alasan pembatalan',
      placeholder: 'mis. salah scan, unitnya milik pelanggan lain',
      confirmLabel: 'Batalkan catatan',
      multiline: true,
      required: true,
    })
    if (reason == null) return
    try {
      const updated = await cancelRecoveredAsset(workOrderId, row.id, reason.trim() || null)
      setRows((prev) => prev.map((it) => (it.id === updated.id ? updated : it)))
      toast.success('Catatan penarikan dibatalkan')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal membatalkan catatan penarikan')
    }
  }

  // Sunyi selama memuat, lalu menampilkan diri sendiri hanya bila memang relevan.
  //
  // Bukan sekadar cek tipe: work order yang tipenya pernah salah lalu diperbaiki tetap boleh
  // punya baris penarikan, dan baris yang sudah tercatat TIDAK BOLEH lenyap dari pandangan
  // hanya karena tipenya berubah — justru baris itulah yang menjelaskan kenapa ada unit
  // nyangkut di van teknisi.
  if (loading) return null
  if (type !== 'DISMANTLE' && rows.length === 0) return null
  // Tak bisa membaca DAN tak bisa mencatat: kartunya tak punya isi yang jujur untuk
  // ditampilkan. Lebih baik tak ada sama sekali daripada kartu kosong yang terbaca
  // seolah "memang belum ada unit yang ditarik".
  if (!canView && !canRecord) return null

  const showForm = canRecord && type === 'DISMANTLE'
  const active = rows.filter((row) => row.cancelledAt == null)

  return (
    <div className="card stack" style={{ gap: '0.75rem' }}>
      <div className="spread" style={{ alignItems: 'baseline', gap: '0.5rem' }}>
        <Text as="h3" size={300} weight="semibold" style={{ margin: 0 }}>Penarikan aset pelanggan</Text>
        <Text as="span" className="muted" size={200}>
          {active.length} unit tercatat ditarik
          {rows.length !== active.length ? ` · ${rows.length - active.length} dibatalkan` : ''}
        </Text>
      </div>

      <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
        Saldo baru bergerak saat work order disetujui, dan unitnya mendarat di van teknisi sebagai
        barang retur — belum jadi stok layak jual. Naikkan ke rak lewat retur gudang.
      </Text>

      {showForm && vanLoadFailed && (
        <Text as="p" className="error" size={200} role="alert" style={{ margin: 0 }}>{VAN_LOAD_FAILED}</Text>
      )}
      {showForm && !vanLoadFailed && vans.length === 0 && (
        <Text as="p" className="error" size={200} role="alert" style={{ margin: 0 }}>
          Belum ada lokasi berjenis kendaraan (van) terdaftar di master data gudang. Unit yang ditarik
          harus mendarat di van teknisi, jadi daftarkan vannya dulu.
        </Text>
      )}
      {showForm && assignees.length === 0 && (
        <Text as="p" className="error" size={200} role="alert" style={{ margin: 0 }}>
          Work order ini belum punya teknisi yang ditugaskan. Tugaskan teknisinya dulu — yang menarik
          unit haruslah orang yang memang dikirim ke rumah pelanggan.
        </Text>
      )}

      {showForm && (
        <div className="row wrap" style={{ gap: '0.5rem', alignItems: 'flex-end' }}>
          <TextField
            label="Nomor seri unit ditarik"
            value={serialNumber}
            onChange={(_, data) => setSerialNumber(data.value)}
            placeholder="Pindai atau ketik nomor seri"
            // Pemindai barcode mengetikkan serial lalu menekan Enter. Tanpa penanganan ini,
            // Enter tidak melakukan apa-apa (atau men-submit form induk) dan teknisi harus
            // meletakkan pemindainya untuk mengetuk tombol tiap unit.
            onKeyDown={(event) => {
              if (event.key !== 'Enter') return
              event.preventDefault()
              if (!busy) void submit()
            }}
            input={{ ref: serialRef }}
            style={{ flex: 1, minWidth: 180 }}
          />
          <SelectField
            label="Teknisi"
            value={technicianId}
            onChange={(_, data) => setTechnicianId(data.value)}
            style={{ minWidth: 160 }}
          >
            {/* Diisi dari roster WO ini, BUKAN `/api/users`: yang mencabut ONT adalah orang
                yang ditugaskan di tiket ini, dan menembak direktori pengguna di sini menuntut
                `iam.user.view` yang justru tidak dipegang aktornya. */}
            <option value="">Pilih teknisi…</option>
            {assignees.map((person) => (
              <option key={person.id} value={person.id}>
                {person.name ?? 'Tanpa nama'}
              </option>
            ))}
          </SelectField>
          <SelectField
            label="Van teknisi"
            value={locationId}
            onChange={(_, data) => setLocationId(data.value)}
            disabled={vanLoadFailed}
            style={{ minWidth: 150 }}
          >
            <option value="">Pilih van…</option>
            {vans.map((van) => (
              <option key={van.id} value={van.id}>
                {van.code}
              </option>
            ))}
          </SelectField>
          <SelectField
            label="Kondisi"
            value={condition}
            onChange={(_, data) => setCondition(data.value as RecoveredAssetCondition)}
            style={{ minWidth: 130 }}
          >
            {CONDITIONS.map((value) => (
              <option key={value} value={value}>
                {CONDITION_LABEL[value]}
              </option>
            ))}
          </SelectField>
          <TextField
            label="Catatan (opsional)"
            value={note}
            onChange={(_, data) => setNote(data.value)}
            placeholder="mis. casing retak, adaptor tidak ikut"
            style={{ flex: 1, minWidth: 160 }}
          />
          <Button variant="primary" disabled={busy || vanLoadFailed} onClick={() => void submit()}>
            {busy ? 'Menyimpan…' : 'Catat penarikan'}
          </Button>
        </div>
      )}

      {rows.length === 0 ? (
        <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
          {/* Tanpa izin baca, daftarnya memang TIDAK PERNAH ditanyakan ke server — mengatakan
              "belum ada" di sini adalah klaim yang tak pernah kita verifikasi, dan justru akan
              meyakinkan teknisi bahwa unit yang baru saja ia scan tidak tersimpan. */}
          {canView
            ? 'Belum ada unit yang tercatat ditarik.'
            : 'Daftar unit yang sudah ditarik tidak bisa ditampilkan: butuh izin workorder.material.view. Pencatatan di atas tetap tersimpan.'}
        </Text>
      ) : (
        <div className="table-wrap">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHeaderCell>Nomor seri</TableHeaderCell>
                <TableHeaderCell>MAC</TableHeaderCell>
                <TableHeaderCell>Item</TableHeaderCell>
                <TableHeaderCell>Kondisi</TableHeaderCell>
                <TableHeaderCell>Teknisi</TableHeaderCell>
                <TableHeaderCell>Van</TableHeaderCell>
                <TableHeaderCell>Ditarik</TableHeaderCell>
                <TableHeaderCell>Catatan</TableHeaderCell>
                {canRecord && <TableHeaderCell>Aksi</TableHeaderCell>}
              </TableRow>
            </TableHeader>
            <TableBody>
              {/* Baris yang DIBATALKAN tetap ditampilkan, tidak disembunyikan dan tidak dihapus.
                  Jejak "pernah tercatat ditarik lalu dibatalkan, oleh siapa, alasannya apa" justru
                  bagian yang paling perlu dibaca saat menyelisik selisih stok: tanpa itu, unit yang
                  hilang dari daftar terbaca seolah tak pernah di-scan sama sekali. */}
              {rows.map((row) => {
                const cancelled = row.cancelledAt != null
                return (
                  <TableRow key={row.id} style={cancelled ? { opacity: 0.7 } : undefined}>
                    <TableCell>
                      <div className="stack" style={{ gap: '0.15rem' }}>
                        <span className={cancelled ? 'muted' : undefined} style={cancelled ? { textDecoration: 'line-through' } : undefined}>
                          {row.serialNumber}
                        </span>
                        {cancelled && <Badge tone="critical">Dibatalkan</Badge>}
                      </div>
                    </TableCell>
                    <TableCell className="muted">{row.macAddress ?? '—'}</TableCell>
                    <TableCell>
                      <div className="stack" style={{ gap: '0.15rem' }}>
                        <Text as="span" size={200}>{row.itemName}</Text>
                        <Text as="span" className="muted" size={100}>{row.itemCode}</Text>
                      </div>
                    </TableCell>
                    <TableCell>
                      <Badge tone={row.condition === 'GOOD' ? 'good' : 'warning'}>{CONDITION_LABEL[row.condition]}</Badge>
                    </TableCell>
                    <TableCell>{row.technicianName}</TableCell>
                    {/* Kode vannya dibawa read model. JANGAN menukar `technicianLocationId`
                        jadi nama lewat `/api/inventory/locations`: endpoint itu menuntut
                        `inventory.location.view` yang tidak dipegang pembaca layar ini, dan
                        yang tersisa di layar cuma UUID telanjang. */}
                    <TableCell>{row.technicianLocationCode}</TableCell>
                    <TableCell>
                      <div className="stack" style={{ gap: '0.15rem' }}>
                        <Text as="span" size={200}>{fmt(row.recoveredAt)}</Text>
                        <Text as="span" className="muted" size={100}>oleh {row.recoveredByName}</Text>
                      </div>
                    </TableCell>
                    <TableCell>
                      <div className="stack" style={{ gap: '0.15rem' }}>
                        {row.note && <Text as="span" size={200}>{row.note}</Text>}
                        {cancelled && (
                          <>
                            <Text as="span" className="muted" size={100}>
                              Dibatalkan {fmt(row.cancelledAt)} oleh {row.cancelledByName ?? '—'}
                            </Text>
                            <Text as="span" className="muted" size={100}>
                              Alasan: {row.cancelReason ?? '—'}
                            </Text>
                          </>
                        )}
                        {!row.note && !cancelled && <span className="muted">—</span>}
                      </div>
                    </TableCell>
                    {canRecord && (
                      <TableCell>
                        {cancelled ? (
                          <span className="muted">—</span>
                        ) : (
                          /* Tombolnya tetap DITAMPILKAN saat WO sudah DONE, tapi MATI dan
                             menjelaskan kenapa. Server masih menerima pembatalan setelah WO
                             disetujui padahal saldonya sudah terlanjur bergerak (celah yang
                             tercatat di docs/serah-terima-gudang-pesanan.md §4), sehingga
                             barisnya akan berbunyi "dibatalkan" sementara unitnya nyata-nyata
                             sudah bertambah di van. Dimatikan, bukan disembunyikan: yang
                             menekan tombol ini perlu tahu koreksinya lewat penyesuaian stok
                             gudang — tombol yang lenyap tak mengajarkan apa pun. Ini penutupan
                             DARI SISI KLIEN saja; jalur curl-nya masih terbuka. */
                          <Button
                            size="small"
                            variant="danger"
                            disabled={status === 'DONE'}
                            title={
                              status === 'DONE'
                                ? 'Work order sudah selesai dan saldonya sudah bergerak — koreksi lewat penyesuaian stok gudang, bukan pembatalan catatan ini.'
                                : undefined
                            }
                            onClick={() => void cancel(row)}
                          >
                            Batalkan
                          </Button>
                        )}
                      </TableCell>
                    )}
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </div>
      )}
    </div>
  )
}
