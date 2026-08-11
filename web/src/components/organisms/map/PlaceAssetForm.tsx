import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import { SPLITTER_RATIOS, type SiteView } from '@/api/network'
import type { PageResponse } from '@/api/types'
import { Button, SelectField, TextField } from '@/components/atoms'
import { BladeHead } from '@/components/molecules'
import { ASSET_META, type AssetKind } from '@/map/mapAssets'

/** Vendor OLT yang didukung — selaras dengan daftar di halaman Inventaris. */
const VENDORS = ['ZTE', 'HUAWEI', 'FIBERHOME', 'NOKIA', 'HSGQ', 'OTHER']

/**
 * Form isian perangkat titik baru, muncul setelah lokasi diklik di peta. Field
 * menyesuaikan jenis: Site cukup alamat, ODC/ODP butuh rasio splitter & kapasitas,
 * joint box butuh jumlah tray & kapasitas sambungan (di dalamnya tak ada splitter),
 * ODF butuh POP induk & jumlah port (rak tak punya alamat sendiri — alamatnya POP-nya).
 * Uplink (ODC→OLT feeder, ODP→ODC distribusi) TIDAK diisi di sini — ditetapkan
 * dengan menarik kabel di peta agar fisik = logis dan sumber kebenarannya tunggal.
 * Koordinat diambil dari titik klik (ditampilkan, tak bisa diubah manual di sini).
 */
export function PlaceAssetForm({
  kind,
  lng,
  lat,
  onCancel,
  onSave,
}: {
  kind: AssetKind
  lng: number
  lat: number
  onCancel: () => void
  onSave: (payload: Record<string, unknown>) => void
}) {
  const meta = ASSET_META[kind]
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [address, setAddress] = useState('')
  const [splitterRatio, setSplitterRatio] = useState('1:8')
  // Joint box: 2 tray × 12 sambungan = 24, ukuran closure inline yang paling lazim
  // dipakai untuk menyambung haspel di lapangan.
  const [trayCount, setTrayCount] = useState(2)
  const [capacity, setCapacity] = useState(kind === 'ODP' ? 8 : kind === 'JOINT_BOX' ? 24 : 64)
  // ODF: 24 port = satu panel 1U penuh, ukuran rak POP kecil yang paling lazim.
  const [portCount, setPortCount] = useState(24)
  // OLT & ODF: site induk (wajib), lalu identitas perangkat & kesiapan SNMP (OLT saja).
  const [siteId, setSiteId] = useState('')
  const [sites, setSites] = useState<SiteView[]>([])
  const [vendor, setVendor] = useState('ZTE')
  const [model, setModel] = useState('')
  const [managementIp, setManagementIp] = useState('')
  const [snmpCommunity, setSnmpCommunity] = useState('')
  const [snmpPort, setSnmpPort] = useState('161')

  // Daftar site untuk memilih tempat berdirinya OLT/ODF. Wajib dipilih sebelum simpan:
  // keduanya perangkat DALAM ruangan — mereka selalu berdiri di dalam sebuah POP.
  useEffect(() => {
    if (kind !== 'OLT' && kind !== 'ODF') return
    let alive = true
    api
      .get<PageResponse<SiteView>>('/api/sites?size=100')
      .then((page) => {
        if (alive) setSites(page.content)
      })
      .catch(() => {
        /* pemilih site opsional untuk pemuatan — tetap wajib saat simpan */
      })
    return () => {
      alive = false
    }
  }, [kind])

  // Normalisasi kode aset: rapikan spasi & seragamkan huruf besar (kode aset konvensinya uppercase).
  const sanitizeCode = (raw: string) => raw.trim().replace(/\s+/g, ' ').toUpperCase()

  const submit = () => {
    const base: Record<string, unknown> = { code: sanitizeCode(code), name: name.trim() }
    if (kind === 'OLT') {
      base.siteId = siteId
      base.vendor = vendor
      if (model.trim()) base.model = model.trim()
      if (managementIp.trim()) base.managementIp = managementIp.trim()
      if (snmpCommunity.trim()) base.snmpCommunity = snmpCommunity.trim()
      base.snmpPort = Number(snmpPort) || 161
      onSave(base)
      return
    }
    // Rak tak beralamat sendiri: ia berdiri di dalam POP, dan alamat POP itulah
    // alamatnya. Yang menentukan ukurannya jumlah port — tiap port berkepala dua.
    if (kind === 'ODF') {
      base.siteId = siteId
      base.portCount = portCount
      onSave(base)
      return
    }
    if (address.trim()) base.address = address.trim()
    if (kind === 'SITE') {
      onSave(base)
      return
    }
    // Joint box tak berisi splitter: ukurannya tray & jumlah sambungan yang muat.
    if (kind === 'JOINT_BOX') {
      base.trayCount = trayCount
      base.capacity = capacity
      onSave(base)
      return
    }
    // Kosong = kabinet tanpa splitter (cross-connect), bukan isian yang terlewat.
    base.splitterRatio = splitterRatio || null
    base.capacity = capacity
    onSave(base)
  }

  // OLT & ODF wajib pilih site; aset lain hanya butuh kode + nama.
  const needsSite = kind === 'OLT' || kind === 'ODF'
  const canSubmit = code.trim() !== '' && name.trim() !== '' && (!needsSite || siteId !== '')

  return (
    <aside className="map-panel blade">
      <BladeHead
        title={`${meta.label} baru`}
        subtitle={`${lat.toFixed(6)}, ${lng.toFixed(6)} · seret pin untuk menggeser`}
        onClose={onCancel}
        closeLabel="Batal"
      />
      <div className="blade-body stack">
        <TextField
          label="Kode"
          value={code}
          onChange={(_, data) => setCode(data.value)}
          placeholder={kind === 'JOINT_BOX' ? 'JB-001' : `${kind}-001`}
        />
        <TextField label="Nama" value={name} onChange={(_, data) => setName(data.value)} />
        {kind === 'OLT' && (
          <>
            <SelectField label="Site induk" value={siteId} onChange={(_, data) => setSiteId(data.value)}>
              <option value="">— pilih site —</option>
              {sites.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.code} — {s.name}
                </option>
              ))}
            </SelectField>
            <div className="row" style={{ gap: '0.5rem' }}>
              <SelectField label="Vendor" value={vendor} onChange={(_, data) => setVendor(data.value)} style={{ flex: 1 }}>
                {VENDORS.map((v) => (
                  <option key={v} value={v}>
                    {v}
                  </option>
                ))}
              </SelectField>
              <TextField
                label={<>Model <span className="muted">(opsional)</span></>}
                value={model}
                onChange={(_, data) => setModel(data.value)}
                style={{ flex: 1 }}
              />
            </div>
            <TextField
              label={<>IP manajemen <span className="muted">(opsional)</span></>}
              value={managementIp}
              onChange={(_, data) => setManagementIp(data.value)}
              placeholder="10.0.0.1"
            />
            <div className="row" style={{ gap: '0.5rem' }}>
              <TextField
                label={<>SNMP community <span className="muted">(opsional)</span></>}
                value={snmpCommunity}
                onChange={(_, data) => setSnmpCommunity(data.value)}
                placeholder="public"
                style={{ flex: 1 }}
              />
              <TextField
                label="Port SNMP"
                type="number"
                min={1}
                max={65535}
                value={snmpPort}
                onChange={(_, data) => setSnmpPort(data.value)}
                style={{ width: '6.5rem' }}
              />
            </div>
          </>
        )}
        {kind === 'ODF' && (
          <>
            <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>
              Rak terminasi di dalam POP: tempat kabel luar BERHENTI. Seratnya dilas ke pigtail
              di sisi belakang port, lalu patchcord dari sisi depannya yang mencolok ke port PON —
              jadi kabel lapangan tak pernah menempel langsung ke badan OLT.
            </p>
            <SelectField label="POP induk" value={siteId} onChange={(_, data) => setSiteId(data.value)}>
              <option value="">— pilih POP —</option>
              {sites.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.code} — {s.name}
                </option>
              ))}
            </SelectField>
            <TextField
              label="Jumlah port"
              type="number"
              min={1}
              max={1152}
              value={String(portCount)}
              onChange={(_, data) => setPortCount(Number(data.value))}
            />
          </>
        )}
        {kind !== 'SITE' && kind !== 'OLT' && kind !== 'ODF' && (
          <TextField
            label={<>Alamat <span className="muted">(opsional)</span></>}
            value={address}
            onChange={(_, data) => setAddress(data.value)}
          />
        )}
        {kind === 'SITE' && (
          <TextField label="Alamat" value={address} onChange={(_, data) => setAddress(data.value)} />
        )}
        {kind === 'ODP' && (
          <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>
            ODC induk ditetapkan dengan menarik kabel distribusi dari ODC ke ODP ini di peta —
            bukan di sini — supaya jalur fisik & data uplink selalu sinkron.
          </p>
        )}
        {kind === 'JOINT_BOX' && (
          <>
            <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>
              Kotak sambung: tempat dua haspel kabel bertemu, jalur bercabang di persimpangan,
              atau kabel putus disambung darurat. Tak ada splitter di dalamnya — serat masuk
              disambung langsung ke serat keluar.
            </p>
            <div className="row" style={{ gap: '0.5rem' }}>
              <TextField
                label="Jumlah tray"
                type="number"
                min={1}
                max={64}
                value={String(trayCount)}
                onChange={(_, data) => setTrayCount(Number(data.value))}
                style={{ flex: 1 }}
              />
              <TextField
                label="Kapasitas sambungan"
                type="number"
                min={1}
                max={1536}
                value={String(capacity)}
                onChange={(_, data) => setCapacity(Number(data.value))}
                style={{ flex: 1 }}
              />
            </div>
          </>
        )}
        {(kind === 'ODC' || kind === 'ODP') && (
          <div className="row" style={{ gap: '0.5rem' }}>
            <SelectField
              label="Splitter"
              value={splitterRatio}
              onChange={(_, data) => setSplitterRatio(data.value)}
              style={{ flex: 1 }}
            >
              {/* Kabinet cross-connect memang tak berisi splitter — dan modul kedua,
                  ketiga, dst. ditambahkan belakangan dari panel "Isi kabinet". */}
              <option value="">Tanpa splitter</option>
              {SPLITTER_RATIOS.map((r) => (
                <option key={r} value={r}>
                  {r}
                </option>
              ))}
            </SelectField>
            <TextField
              label="Kapasitas"
              type="number"
              min={1}
              max={kind === 'ODP' ? 256 : 1024}
              value={String(capacity)}
              onChange={(_, data) => setCapacity(Number(data.value))}
              style={{ flex: 1 }}
            />
          </div>
        )}
        <div className="row">
          <Button variant="primary" disabled={!canSubmit} onClick={submit}>
            Simpan {meta.label}
          </Button>
          <Button variant="subtle" onClick={onCancel}>
            Batal
          </Button>
        </div>
      </div>
    </aside>
  )
}
