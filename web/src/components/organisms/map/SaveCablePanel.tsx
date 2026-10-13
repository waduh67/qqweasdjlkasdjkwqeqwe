import { ResourceStep } from '@/components/organisms/ResourceStep'
import { Blade } from '@/components/organisms/Blade'
import { CreationSummary, useCreationReview } from '@/components/organisms/CreationReview'
import { useEffect, useMemo, useState } from 'react'
import { MessageBar, MessageBarBody, Text, ToggleButton } from '@fluentui/react-components'
import { api } from '@/api/client'
import type {
  CableAttachmentRole,
  CableInstallation,
  CableOwnership,
  CablePortOption,
  CableType,
  NodeKind,
  OdpInspection,
  OnuView,
} from '@/api/network'
import { CABLE_ATTACHMENT_ROLE_LABEL } from '@/api/network'
import { Button, SelectField, TextField } from '@/components/atoms'
import {
  CODE_MAX,
  DEFAULT_CORES,
  TYPE_LABEL,
  autoCableCode,
  formatLength,
  waypointCommands,
  type SourcePort,
} from '@/map/cableFormat'
import { legalCableTypes, type SnappedDevice } from '@/map/cableTool'
import { CablePhysicalFields } from './CablePhysicalFields'
import { CableSourceFields } from './CableSourceFields'

export function SaveCablePanel({
  from,
  to,
  fromKind,
  fromId,
  toKind,
  toId,
  cableType,
  lengthMeters,
  waypoints,
  onRemoveWaypoint,
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
  /** Kotak yang disinggahi di tengah bentang, urut dari pangkal — hasil gambar. */
  waypoints: SnappedDevice[]
  /** Buang satu singgahan dari gambar (titik rutenya ikut hilang). */
  onRemoveWaypoint: (nodeId: string) => void
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
    /** Singgahan di tengah bentang + perannya, urut dari pangkal. */
    waypoints: Array<{ nodeKind: NodeKind; nodeId: string; role: CableAttachmentRole }>
  }) => void | Promise<void>
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
  // Peran tiap singgahan; yang tak disebut berarti masih bawaan (dikupas).
  const [roles, setRoles] = useState<Record<string, CableAttachmentRole>>({})

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
  const [srcError, setSrcError] = useState(false), [srcAttempt, setSrcAttempt] = useState(0)

  useEffect(() => {
    if (customerDrop) return
    let alive = true
    setSrcOptions(null)
    setSrcError(false)
    setSrcPort(null)
    void api
      .get<CablePortOption[]>(`/api/cables/source-ports?kind=${fromKind}&id=${fromId}`)
      .then((opts) => {
        if (alive) setSrcOptions(opts)
      })
      .catch(() => {
        if (alive) { setSrcOptions([]); setSrcError(true) }
      })
    return () => {
      alive = false
    }
  }, [customerDrop, fromKind, fromId, srcAttempt])

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
    : !srcError && srcOptions != null && (srcPort != null || (portlessSource && srcOptions.length === 0))
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

  const buildPayload = (): Parameters<typeof onSave>[0] => ({
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
      waypoints: waypointCommands(waypoints, roles),
    })

  const creation = useCreationReview(true)
  const [saveError, setSaveError] = useState<string | null>(null)
  const submit = async () => {
    if (!canSave || creation.beforeSave()) return
    setSaveError(null)
    try { await onSave(buildPayload()) }
    catch (error) { setSaveError(error instanceof Error ? error.message : 'Gagal menyimpan kabel.') }
    finally { creation.finish() }
  }

  return (
    <Blade open title="Kabel baru" onClose={onCancel}
      creation={{ ...creation, steps: [{ key: 'cable', label: 'Kabel' }, { key: 'ports', label: 'Sambungan' }], prepare: () => void submit(), summary: <><CreationSummary rows={[
        ['Nama', name], ['Jenis kabel', TYPE_LABEL[type]], ['Rute', `${from} → ${to}`],
        ['Panjang', formatLength(lengthMeters)], ['Jumlah core', coreCount], ['Kode', codeTouched ? code : 'Otomatis'],
        ['Port sumber', customerDrop ? selectedPort ?? '—' : srcOptions?.find(port => srcPort && (port.ponPortId ? port.ponPortId === srcPort.ponPortId : port.portNumber === srcPort.portNumber))?.label ?? 'Sambungan core'],
      ]} />{saveError && <p role="alert">{saveError}</p>}</> }}
      footer={<><Button disabled={creation.busy} onClick={onCancel}>Batal</Button><Button variant="primary" disabled={!canSave || creation.busy} onClick={() => void submit()}>Simpan kabel</Button></>}>
      <div className="blade-body stack"><ResourceStep step="cable">
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
            <Text as="span" className="muted" size={100}>
              Gunakan jenis kabel yang masuk dari sisi hulu.
            </Text>
          </div>
        )}
        {/* Ruas kotak-ke-kotak: satu-satunya bentuk yang gampang salah gambar.
            Diperingatkan SEBELUM disimpan, sebab membatalkan gambar jauh lebih
            murah daripada membongkar ruas yang core-nya sudah dipakai orang. */}
        {fromKind === 'ODP' && toKind === 'ODP' && (
          <MessageBar intent="info">
            <MessageBarBody>
              Gunakan ruas ini jika splitter {from} terhubung ke splitter {to}.
              Untuk satu kabel yang melewati beberapa kotak, gambar kabel menerus dan catat
              titik kupasnya di meja sambung. Ini menjaga perhitungan panjang dan jalur koneksi.
            </MessageBarBody>
          </MessageBar>
        )}
        {/* Bentuk asli kabel distribusi: satu selubung menyusuri gang, mampir di
            beberapa kotak, berhenti di yang terakhir. Yang tercatat di sini
            menentukan di kotak mana core-nya boleh disambung nanti — jadi peran
            tiap singgahan bisa dibetulkan sekarang, selagi orangnya masih ingat. */}
        {waypoints.length > 0 && (
          <div className="stack" style={{ gap: '0.35rem' }}>
            <Text as="span" size={300} weight="semibold">
              Mampir di {waypoints.length} kotak
            </Text>
            {waypoints.map((w, i) => (
              <div key={w.id} className="row" style={{ gap: '0.4rem', alignItems: 'flex-end' }}>
                <Text as="span" className="tnum muted" size={100} style={{ paddingBottom: '0.4rem' }}>
                  {i + 1}.
                </Text>
                <Text as="span" size={200} style={{ flex: 1, paddingBottom: '0.4rem' }}>{w.code}</Text>
                <SelectField
                  aria-label={`Keadaan selubung di ${w.code}`}
                  value={roles[w.id] ?? 'TAPPED'}
                  onChange={(_, data) =>
                    setRoles((prev) => ({ ...prev, [w.id]: data.value as CableAttachmentRole }))
                  }
                >
                  <option value="TAPPED">{CABLE_ATTACHMENT_ROLE_LABEL.TAPPED}</option>
                  <option value="PASSING">{CABLE_ATTACHMENT_ROLE_LABEL.PASSING}</option>
                </SelectField>
                <Button variant="subtle" onClick={() => onRemoveWaypoint(w.id)}>
                  Buang
                </Button>
              </div>
            ))}
            <Text as="span" className="muted" size={100}>
              Panjang kabel dihitung untuk seluruh jalur yang digambar.
            </Text>
          </div>
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
              ? 'Kode harus unik.'
              : 'Kode dibuat otomatis dari kedua ujung kabel.'
          }
          onChange={(_, data) => {
            setCodeTouched(true)
            setCode(data.value)
          }}
        />
        <TextField label="Nama" required value={name} onChange={(_, data) => setName(data.value)} />
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

        </ResourceStep><ResourceStep step="ports">
        {!customerDrop && <CableSourceFields from={from} to={to} fromKind={fromKind} options={srcOptions}
          selected={srcPort} onPick={setSrcPort} error={srcError} onRetry={() => setSrcAttempt(value => value + 1)} />}

        {customerDrop && (
          <div className="stack" style={{ gap: '0.4rem' }}>
            <div className="spread">
              <Text as="span" size={300} weight="semibold">Port ODP {odp?.code ?? from}</Text>
              {odp && (
                <Text as="span" className="muted" size={200}>
                  {odp.usedPorts}/{odp.capacity} terpakai
                </Text>
              )}
            </div>
            {loadingPorts ? (
              <Text as="span" className="muted" size={200}>
                Memuat port…
              </Text>
            ) : odp ? (
              <>
                <PortGrid
                  inspection={odp}
                  selected={selectedPort}
                  ownPort={onu?.odpId === odp.odpId ? onu?.odpPortNumber ?? null : null}
                  onPick={canAssignPort && onu ? setSelectedPort : undefined}
                />
                {!onu ? (
                  <Text as="span" className="muted" size={100}>
                    Pasang ONU pelanggan sebelum menyambungkan kabel drop.
                  </Text>
                ) : !canAssignPort ? (
                  <Text as="span" className="muted" size={100}>
                    Butuh izin <Text as="span" className="tnum">customer.onu.assign</Text> untuk menautkan port.
                  </Text>
                ) : selectedPort == null ? (
                  <Text as="span" className="muted" size={100}>
                    Pilih slot kosong untuk menautkan ONU pelanggan.
                  </Text>
                ) : (
                  <Text as="span" className="muted" size={100}>
                    ONU {onu.serialNumber} → slot {selectedPort}
                  </Text>
                )}
              </>
            ) : (
              <Text as="span" className="muted" size={200}>
                Gagal memuat port ODP.
              </Text>
            )}
          </div>
        )}


      </ResourceStep></div>
    </Blade>
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
          <ToggleButton
            key={n}
            size="small"
            checked={isSelected}
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
              textAlign: 'center',
            }}
          >
            <Text as="span" block className="tnum" weight="semibold">
              {n}
            </Text>
            <Text as="span" block size={100} style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {occ ? occ.customerCode : '·'}
            </Text>
          </ToggleButton>
        )
      })}
    </div>
  )
}
