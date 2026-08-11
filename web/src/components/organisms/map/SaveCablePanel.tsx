import { useEffect, useMemo, useState } from 'react'
import { MessageBar, MessageBarBody } from '@fluentui/react-components'
import { api } from '@/api/client'
import type {
  CableInstallation,
  CableOwnership,
  CablePortOption,
  CableType,
  NodeKind,
  OdpInspection,
  OnuView,
} from '@/api/network'
import { Button, SelectField, TextField } from '@/components/atoms'
import { BladeHead } from '@/components/molecules'
import { CODE_MAX, DEFAULT_CORES, TYPE_LABEL, autoCableCode, formatLength, type SourcePort } from '@/map/cableFormat'
import { legalCableTypes } from '@/map/cableTool'
import { CablePhysicalFields } from './CablePhysicalFields'

export function SaveCablePanel({
  from,
  to,
  fromKind,
  fromId,
  toKind,
  toId,
  cableType,
  lengthMeters,
  canAssignPort,
  onCancel,
  onSave,
}: {
  from: string
  to: string
  /** Jenis perangkat ujung awal — menentukan bentuk port sumber (PON/kaki/slot). */
  fromKind: NodeKind
  /** Id perangkat ujung awal (untuk drop = ODP tempat port dipilih). */
  fromId: string
  /** Jenis perangkat ujung akhir — pembeda drop ke pelanggan vs drop ke joint box. */
  toKind: NodeKind
  /** Id perangkat ujung akhir (untuk drop = pelanggan yang ONU-nya ditautkan). */
  toId: string
  cableType: CableType
  lengthMeters: number
  canAssignPort: boolean
  onCancel: () => void
  onSave: (form: {
    /** Kode di label selubung; kosong = server yang merakitnya. */
    code?: string
    name: string
    coreCount: number
    /** Jenis akhir yang dipilih operator — sama dengan tersirat kecuali joint box→joint box. */
    cableType: CableType
    fromPonPortId?: string
    fromPortNumber?: number
    onuId?: string
    installation: CableInstallation | null
    ownership: CableOwnership
  }) => void
}) {
  // Jenis kabel hampir selalu tersirat dari sepasang ujungnya, jadi ia datang sebagai
  // prop dan operator tak ditanya hal yang sudah jelas. Pengecualiannya cuma saat
  // ujung akhirnya joint box — kotak sambung tak mengaku melayani apa (lihat
  // [legalCableTypes]) — dan di situ saja pemilih di bawah muncul.
  const typeOptions = useMemo(() => legalCableTypes(fromKind, toKind), [fromKind, toKind])
  const ambiguousType = typeOptions.length > 1
  const [type, setType] = useState<CableType>(cableType)
  const [name, setName] = useState(`${TYPE_LABEL[cableType]} ${from} → ${to}`)
  const [coreCount, setCoreCount] = useState(DEFAULT_CORES[cableType])
  const [code, setCode] = useState('')
  // Sekali kolom kode disentuh, ia berhenti ikut berubah. Kode yang DIKETIK orang
  // biasanya sudah menuruti penomoran perusahaan, dan menimpanya diam-diam cuma
  // karena jenis/port diperbaiki setelahnya berarti kertas yang dibawa ke lapangan
  // berbeda dari yang tersimpan.
  const [codeTouched, setCodeTouched] = useState(false)
  // Sengaja tanpa prasetel per jenis kabel: menebak "drop pasti udara" akan
  // menuliskan hasil survei palsu ke basis data, dan yang membayarnya adalah
  // teknisi yang datang bertangga ke gangguan di dalam duct.
  const [installation, setInstallation] = useState<CableInstallation | ''>('')
  const [ownership, setOwnership] = useState<CableOwnership>('OWNED')

  /**
   * Drop yang benar-benar berujung di ONU pelanggan lewat slot ODP — hanya di situ
   * peta port & penautan ONU berlaku. Drop yang salah satu ujungnya joint box (mis.
   * sambungan haspel di tengah jalur drop) tak punya ONU untuk ditautkan, dan
   * menuntutnya membuat kabel yang sah jadi tak bisa disimpan.
   */
  const customerDrop = cableType === 'DROP' && fromKind === 'ODP' && toKind === 'CUSTOMER'

  /**
   * Simpul yang tak menyebut "port asal" untuk kabel yang berangkat darinya: POP
   * tak melalui PON port, joint box menyambung serat langsung (tak ada kaki yang
   * dicolok), dan ODF — portnya bernomor, tapi yang dicolok di sana patchcord,
   * bukan ujung kabel outdoor.
   *
   * ODC & ODP kini ikut: sebuah selubung berangkat dari kabinet lewat SERATNYA,
   * satu core ke satu kaki splitter, dan pasangan itu dicatat di meja sambung yang
   * menyebut modul & core-nya. Satu nomor kaki di ujung kabel cuma sanggup
   * menyimpan satu dari delapan pasangan yang sebenarnya ada.
   *
   * Daftar port kosong di sini SAH — beda dari OLT tanpa PON port, yang kosongnya
   * berarti perangkatnya belum disiapkan.
   */
  const portlessSource = fromKind !== 'OLT'

  // Feeder dari OLT: pilih PON port sumber — di situ kabel memang benar-benar
  // dicolok, dan pilihannya sekaligus menyetel uplink simpul hilir.
  const [srcOptions, setSrcOptions] = useState<CablePortOption[] | null>(customerDrop ? [] : null)
  const [srcPort, setSrcPort] = useState<SourcePort | null>(null)

  useEffect(() => {
    if (customerDrop) return
    let alive = true
    setSrcOptions(null)
    void api
      .get<CablePortOption[]>(`/api/cables/source-ports?kind=${fromKind}&id=${fromId}`)
      .then((opts) => {
        if (alive) setSrcOptions(opts)
      })
      .catch(() => {
        if (alive) setSrcOptions([])
      })
    return () => {
      alive = false
    }
  }, [customerDrop, fromKind, fromId])

  // Untuk kabel drop, tampilkan peta port ODP tujuan supaya port tidak ditebak.
  const [odp, setOdp] = useState<OdpInspection | null>(null)
  const [onu, setOnu] = useState<OnuView | null>(null)
  const [loadingPorts, setLoadingPorts] = useState(customerDrop)
  const [selectedPort, setSelectedPort] = useState<number | null>(null)

  useEffect(() => {
    if (!customerDrop) return
    let alive = true
    setLoadingPorts(true)
    void (async () => {
      try {
        const [odpInsp, onus] = await Promise.all([
          api.get<OdpInspection>(`/api/gis/odps/${fromId}`),
          api
            .get<OnuView[]>(`/api/customers/${toId}/onus`)
            .catch(() => [] as OnuView[]),
        ])
        if (!alive) return
        setOdp(odpInsp)
        // ONU aktif pelanggan (yang belum dibongkar) — sasaran penautan port.
        const active = onus.find((o) => o.status !== 'DISMANTLED') ?? onus[0] ?? null
        setOnu(active)
        // Prasetel ke port ONU saat ini bila memang sudah di ODP ini.
        if (active?.odpId === odpInsp.odpId && active.odpPortNumber != null) {
          setSelectedPort(active.odpPortNumber)
        }
      } finally {
        if (alive) setLoadingPorts(false)
      }
    })()
    return () => {
      alive = false
    }
  }, [customerDrop, fromId, toId])

  /**
   * Bunyi kode yang akan dipakai — persis aturan server, jadi kolomnya bisa dibiarkan
   * apa adanya. Drop ke pelanggan berlabuh pada ODP + nomor slotnya, bukan pada kode
   * pelanggan: begitulah orang lapangan menyebutnya ("drop dari kotak itu, port tiga"),
   * dan kode pelanggan memang tak dikenal modul jaringan.
   */
  const autoCode = useMemo(
    () => autoCableCode(type, customerDrop ? [from, selectedPort != null ? `P${selectedPort}` : ''] : [from, to]),
    [type, customerDrop, from, to, selectedPort],
  )

  useEffect(() => {
    if (!codeTouched) setCode(autoCode)
  }, [autoCode, codeTouched])

  // Kesiapan simpan: feeder/distribusi WAJIB port sumber terpilih. Pengecualian
  // "daftar kosong boleh" berlaku untuk semua simpul yang tak menyebut port asal
  // (POP, joint box, ODF, kabinet & kotak — lihat [portlessSource]). OLT tanpa PON
  // port juga berdaftar kosong, TAPI di situ port tetap wajib — menyimpan tanpa
  // port berarti uplink diam-diam tak ter-set (feeder "yatim").
  const sourceReady = customerDrop
    ? true
    : srcOptions != null && (srcPort != null || (portlessSource && srcOptions.length === 0))
  const dropReady = !customerDrop || onu != null
  const canSave = name.trim() !== '' && sourceReady && dropReady

  /**
   * Ganti jenis ikut memperbarui nama & jumlah core BILA keduanya masih persis
   * bawaan jenis sebelumnya — nilai bawaan jelas bukan ketikan orang, sedangkan
   * yang sudah disunting tak boleh ditimpa diam-diam.
   */
  const pickType = (next: CableType) => {
    if (name === `${TYPE_LABEL[type]} ${from} → ${to}`) setName(`${TYPE_LABEL[next]} ${from} → ${to}`)
    if (coreCount === DEFAULT_CORES[type]) setCoreCount(DEFAULT_CORES[next])
    setType(next)
  }

  const submit = () =>
    onSave({
      // Kode yang belum disentuh dikirim KOSONG walau kolomnya terisi: isinya cuma
      // pratinjau dari aturan yang sama, dan membiarkan server yang merakit membuat
      // ruas kedua antara sepasang kotak yang sama dapat akhiran angka — bukan gagal
      // simpan gara-gara bentrok nama yang tak pernah diketik siapa pun.
      code: codeTouched ? code.trim() || undefined : undefined,
      name,
      coreCount,
      cableType: type,
      fromPonPortId: srcPort?.ponPortId ?? undefined,
      fromPortNumber: customerDrop ? selectedPort ?? undefined : srcPort?.portNumber ?? undefined,
      onuId: customerDrop && onu && selectedPort != null ? onu.id : undefined,
      installation: installation === '' ? null : installation,
      ownership,
    })

  return (
    <aside className="map-panel blade">
      <BladeHead
        title="Kabel baru"
        subtitle={`${TYPE_LABEL[type]} · ${from} → ${to} · ${formatLength(lengthMeters)}`}
        onClose={onCancel}
        closeLabel="Batal"
      />
      <div className="blade-body stack">
        {ambiguousType && (
          <div className="stack" style={{ gap: '0.35rem' }}>
            <SelectField
              label="Jenis kabel"
              value={type}
              onChange={(_, data) => pickType(data.value as CableType)}
            >
              {typeOptions.map((t) => (
                <option key={t} value={t}>{TYPE_LABEL[t]}</option>
              ))}
            </SelectField>
            <span className="muted" style={{ fontSize: '0.78rem' }}>
              Ruas yang berakhir di joint box tak menunjukkan jenisnya sendiri — pilih jenis
              kabel HULUNYA, sebab kotak sambung cuma menerus apa yang masuk.
            </span>
          </div>
        )}
        {/* Ruas kotak-ke-kotak: satu-satunya bentuk yang gampang salah gambar.
            Diperingatkan SEBELUM disimpan, sebab membatalkan gambar jauh lebih
            murah daripada membongkar ruas yang core-nya sudah dipakai orang. */}
        {fromKind === 'ODP' && toKind === 'ODP' && (
          <MessageBar intent="info">
            <MessageBarBody>
              Ruas kotak ke kotak cuma sah bila kaki splitter {from} benar-benar menyuapi {to}
              (splitter bertingkat). Kalau ini sebenarnya SATU selubung yang lewat di depan
              kedua kotak, gambar satu kabel menerus sampai kotak terakhir lalu kupas di tiap
              kotak lewat meja sambung — panjang materialnya tak dihitung dobel dan simulasi
              putusnya jadi jujur.
            </MessageBarBody>
          </MessageBar>
        )}
        {/* Kode di atas nama: inilah yang disebut lewat radio dan ditulis di label
            selubung, sedangkan nama cuma dibaca di layar. Terisi sendiri supaya tak
            seorang pun tergoda mengosongkannya, tapi tetap bisa ditimpa penomoran
            perusahaan yang sudah berjalan. */}
        <TextField
          label="Kode"
          value={code}
          maxLength={CODE_MAX}
          hint={
            codeTouched
              ? 'Dipakai apa adanya — kalau sudah dipakai kabel lain, simpannya ditolak.'
              : 'Terisi otomatis dari kedua ujungnya. Biarkan saja: ruas kedua ke tujuan yang sama otomatis diberi akhiran angka.'
          }
          onChange={(_, data) => {
            setCodeTouched(true)
            setCode(data.value)
          }}
        />
        <TextField label="Nama" value={name} onChange={(_, data) => setName(data.value)} />
        <TextField
          label="Jumlah core"
          type="number"
          min={1}
          max={288}
          value={String(coreCount)}
          onChange={(_, data) => setCoreCount(Number(data.value))}
        />
        <CablePhysicalFields
          installation={installation}
          ownership={ownership}
          onInstallation={(value) => setInstallation(value ?? '')}
          onOwnership={setOwnership}
        />

        {!customerDrop && (
          <div className="stack" style={{ gap: '0.4rem' }}>
            <span style={{ fontWeight: 600, fontSize: '0.9rem' }}>Port sumber {from}</span>
            {srcOptions == null ? (
              <span className="muted" style={{ fontSize: '0.82rem' }}>
                Memuat port…
              </span>
            ) : srcOptions.length === 0 ? (
              <span className="muted" style={{ fontSize: '0.78rem' }}>
                {fromKind === 'SITE'
                  ? 'Feeder dari POP tak melalui PON port — langsung tersambung.'
                  : fromKind === 'JOINT_BOX'
                    ? 'Joint box tak punya port — serat masuk disambung langsung ke serat keluar, jadi pasangan core-nya diatur di sambungan kotak ini, bukan di sini.'
                    : fromKind === 'ODF'
                      ? 'Port ODF memang bernomor, tapi yang dicolok di sana patchcord — bukan ujung kabel luar. Kabel ini menempel lewat sambungan di sisi belakang portnya, diatur di layar sambungan rak.'
                      : fromKind === 'ODC' || fromKind === 'ODP'
                        ? 'Kabel ini berangkat lewat SERATNYA, bukan lewat satu kaki splitter: tiap core disambung ke kaki yang berbeda. Pasangannya diatur di meja sambung kotak ini setelah kabelnya tergambar.'
                        : fromKind === 'OLT'
                          ? 'OLT ini belum punya PON port. Tambahkan dulu di detail OLT (tab PON Port) sebelum menarik feeder.'
                          : 'Tak ada port keluaran di simpul ini — tak bisa menarik kabel dari sini.'}
              </span>
            ) : (
              <>
                <SourcePortGrid options={srcOptions} selected={srcPort} onPick={setSrcPort} />
                <span className="muted" style={{ fontSize: '0.78rem' }}>
                  {srcPort == null
                    ? 'Pilih port keluaran dulu — kabel tak bisa ditarik tanpa port.'
                    : `Menarik kabel ini otomatis menyetel uplink ${to}.`}
                </span>
              </>
            )}
          </div>
        )}

        {customerDrop && (
          <div className="stack" style={{ gap: '0.4rem' }}>
            <div className="spread">
              <span style={{ fontWeight: 600, fontSize: '0.9rem' }}>Port ODP {odp?.code ?? from}</span>
              {odp && (
                <span className="muted" style={{ fontSize: '0.8rem' }}>
                  {odp.usedPorts}/{odp.capacity} terpakai
                </span>
              )}
            </div>
            {loadingPorts ? (
              <span className="muted" style={{ fontSize: '0.82rem' }}>
                Memuat port…
              </span>
            ) : odp ? (
              <>
                <PortGrid
                  inspection={odp}
                  selected={selectedPort}
                  ownPort={onu?.odpId === odp.odpId ? onu?.odpPortNumber ?? null : null}
                  onPick={canAssignPort && onu ? setSelectedPort : undefined}
                />
                {!onu ? (
                  <span className="muted" style={{ fontSize: '0.78rem' }}>
                    Pelanggan belum punya ONU terpasang — kabel drop tak bisa ditarik ke sini.
                  </span>
                ) : !canAssignPort ? (
                  <span className="muted" style={{ fontSize: '0.78rem' }}>
                    Butuh izin <span className="tnum">customer.onu.assign</span> untuk menautkan port.
                  </span>
                ) : selectedPort == null ? (
                  <span className="muted" style={{ fontSize: '0.78rem' }}>
                    Pilih slot kosong untuk menautkan ONU pelanggan.
                  </span>
                ) : (
                  <span className="muted" style={{ fontSize: '0.78rem' }}>
                    ONU {onu.serialNumber} → slot {selectedPort}
                  </span>
                )}
              </>
            ) : (
              <span className="muted" style={{ fontSize: '0.82rem' }}>
                Gagal memuat port ODP.
              </span>
            )}
          </div>
        )}

        <div className="row">
          <Button variant="primary" disabled={!canSave} onClick={submit}>
            Simpan kabel
          </Button>
          <Button variant="subtle" onClick={onCancel}>
            Batal
          </Button>
        </div>
      </div>
    </aside>
  )
}

/**
 * Peta port KELUARAN sumber (feeder/distribusi): PON port OLT, kaki splitter ODC,
 * atau slot ODP. Port yang sudah dipakai kabel lain tampil nonaktif dengan kode
 * kabel penghuninya — menjawab "colok dari port mana" tanpa menabrak yang terisi.
 */
function SourcePortGrid({
  options,
  selected,
  onPick,
}: {
  options: CablePortOption[]
  selected: SourcePort | null
  onPick: (port: SourcePort) => void
}) {
  const isSame = (o: CablePortOption) =>
    selected != null &&
    (o.ponPortId != null ? selected.ponPortId === o.ponPortId : selected.portNumber === o.portNumber)
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(56px, 1fr))', gap: '0.3rem' }}>
      {options.map((o) => {
        const key = o.ponPortId ?? `p${o.portNumber}`
        const isSelected = isSame(o)
        const selectable = !o.occupied
        const bg = isSelected ? 'var(--accent-soft)' : o.occupied ? 'var(--surface-2, rgba(148,163,184,0.15))' : 'transparent'
        const border = isSelected ? 'var(--accent)' : o.occupied ? 'var(--border)' : 'var(--good-ink)'
        return (
          // Tombol native (bukan Fluent Button) supaya inline-style dihormati apa adanya
          // dan tak terkena min-width 96px Fluent yang membuat sel grid meluber & tumpang tindih.
          <button
            key={key}
            type="button"
            disabled={!selectable}
            onClick={selectable ? () => onPick({ ponPortId: o.ponPortId, portNumber: o.portNumber }) : undefined}
            title={o.occupied ? `${o.label} · dipakai kabel ${o.occupiedByCable}` : `${o.label} · kosong`}
            style={{
              minWidth: 0,
              width: '100%',
              boxSizing: 'border-box',
              padding: '0.3rem 0.2rem',
              borderRadius: 6,
              border: `1px solid ${border}`,
              background: bg,
              color: o.occupied ? 'var(--muted)' : 'var(--text)',
              cursor: selectable ? 'pointer' : 'default',
              fontFamily: 'inherit',
              fontSize: '0.7rem',
              lineHeight: 1.2,
              textAlign: 'center',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
            }}
          >
            {o.label}
          </button>
        )
      })}
    </div>
  )
}

/**
 * Peta port sebuah ODP: satu kotak per port, hijau untuk kosong dan abu untuk
 * terpakai (dengan kode pelanggan penghuninya). Menjawab "port mana yang kosong"
 * secara visual, tanpa menebak. Kotak yang bisa dipilih menyala saat ditunjuk.
 */
function PortGrid({
  inspection,
  selected,
  ownPort,
  onPick,
}: {
  inspection: OdpInspection
  selected: number | null
  /** Port yang sudah dihuni ONU pelanggan ini — boleh dipilih ulang. */
  ownPort: number | null
  onPick?: (port: number) => void
}) {
  const free = new Set(inspection.availablePortNumbers)
  const occupantByPort = new Map(inspection.occupants.map((o) => [o.portNumber, o]))
  const ports = Array.from({ length: inspection.capacity }, (_, i) => i + 1)
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(38px, 1fr))', gap: '0.3rem' }}>
      {ports.map((n) => {
        const occ = occupantByPort.get(n)
        const isOwn = n === ownPort
        const selectable = onPick != null && (free.has(n) || isOwn)
        const isSelected = n === selected
        const bg = isSelected
          ? 'var(--accent-soft)'
          : isOwn
            ? 'var(--good-ink)'
            : occ
              ? 'var(--surface-2, rgba(148,163,184,0.15))'
              : 'transparent'
        const border = isSelected ? 'var(--accent)' : free.has(n) ? 'var(--good-ink)' : 'var(--border)'
        return (
          // Tombol native (bukan Fluent Button) supaya inline-style dihormati apa adanya
          // dan tak terkena min-width 96px Fluent yang membuat sel grid meluber & tumpang tindih.
          <button
            key={n}
            type="button"
            disabled={!selectable}
            onClick={selectable ? () => onPick?.(n) : undefined}
            title={occ ? `Port ${n} · ${occ.customerCode} ${occ.customerName}` : `Port ${n} · kosong`}
            style={{
              minWidth: 0,
              width: '100%',
              boxSizing: 'border-box',
              padding: '0.3rem 0',
              borderRadius: 6,
              border: `1px solid ${border}`,
              background: bg,
              color: occ && !isOwn ? 'var(--muted)' : 'var(--text)',
              cursor: selectable ? 'pointer' : 'default',
              fontFamily: 'inherit',
              fontSize: '0.72rem',
              lineHeight: 1.2,
              textAlign: 'center',
            }}
          >
            <div className="tnum" style={{ fontWeight: 600 }}>
              {n}
            </div>
            <div style={{ fontSize: '0.6rem', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {occ ? occ.customerCode : '·'}
            </div>
          </button>
        )
      })}
    </div>
  )
}
