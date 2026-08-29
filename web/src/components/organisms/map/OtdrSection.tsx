import { useState } from 'react'
import { Text, typographyStyles } from '@fluentui/react-components'
import type {
  CableEnd,
  CableView,
  OtdrEventType,
  OtdrLandmark,
  OtdrPlacement,
  OtdrTest,
  RecordOtdrTest,
} from '@/api/network'
import { Button, SelectField, TextField } from '@/components/atoms'
import { IconClose } from '@/components/atoms/icons'
import { formatLength } from '@/map/cableFormat'
import { WHATIF_COLOR } from '@/map/mapStyle'

/** Label peristiwa OTDR dalam bahasa Indonesia — dipakai daftar & dropdown form. */
const OTDR_EVENT_LABEL: Record<OtdrEventType, string> = {
  BREAK: 'Putus',
  HIGH_LOSS: 'Redaman tinggi',
  REFLECTION: 'Pantulan',
  SPLICE: 'Sambungan',
  END: 'Ujung serat',
}

const OTDR_EVENT_OPTIONS = Object.entries(OTDR_EVENT_LABEL) as [OtdrEventType, string][]

/**
 * Bagian uji OTDR di dalam panel kabel: daftar hasil ukur (tiap baris terbang ke
 * titik perkiraannya di peta bila diklik) plus form catat jarak gangguan. Jarak
 * yang dimasukkan adalah panjang serat dari ujung ukur (hulu/hilir); server
 * memetakannya ke titik di jalur kabel — di sini cukup ditampilkan & diplot.
 */
export function OtdrSection({
  cable,
  tests,
  canRecord,
  onRecord,
  onDelete,
  onFocus,
}: {
  cable: CableView
  tests: OtdrTest[] | null
  canRecord: boolean
  onRecord: (form: RecordOtdrTest) => void
  onDelete: (testId: string) => void
  onFocus: (test: OtdrTest) => void
}) {
  const [distance, setDistance] = useState('')
  const [measuredFrom, setMeasuredFrom] = useState<CableEnd>('FROM')
  const [eventType, setEventType] = useState<OtdrEventType>('BREAK')
  const [lossDb, setLossDb] = useState('')
  const [note, setNote] = useState('')

  const distanceNum = Number(distance)
  const canSubmit = distance.trim() !== '' && Number.isFinite(distanceNum) && distanceNum >= 0

  const submit = () => {
    if (!canSubmit) return
    const loss = Number(lossDb)
    onRecord({
      distanceMeters: distanceNum,
      measuredFrom,
      eventType,
      lossDb: lossDb.trim() !== '' && Number.isFinite(loss) ? loss : null,
      note: note.trim() || null,
    })
    setDistance('')
    setLossDb('')
    setNote('')
  }

  const list = tests ?? []
  // Patokan kabelnya sama untuk semua uji — ambil dari hasil mana pun yang ada.
  const landmarks = list[0]?.placement?.landmarks ?? []

  return (
    <div className="stack" style={{ gap: '0.5rem', borderTop: '1px solid var(--line)', paddingTop: '0.6rem' }}>
      <div className="spread">
        <Text as="strong" size={300} weight="semibold">Uji OTDR</Text>
        <Text as="span" className="muted" size={100}>{list.length} hasil</Text>
      </div>

      {/* Penggarisnya lebih dulu: begitu orang tahu kotak apa saja yang berdiri di
          sepanjang kabel ini, "di antara JB-03 dan ODP-05" di bawah bisa langsung
          dicocokkan tanpa membuka layar lain. */}
      {landmarks.length > 0 && <OtdrLandmarkRuler landmarks={landmarks} />}

      {list.length > 0 && (
        <div className="stack" style={{ gap: '0.35rem', maxHeight: 240, overflowY: 'auto' }}>
          {list.map((t) => (
            <div key={t.id} className="stack" style={{ gap: '0.1rem' }}>
              <div className="spread" style={{ gap: '0.4rem', alignItems: 'center' }}>
                <Button
                  variant="subtle"
                  style={{ ...typographyStyles.body1, justifyContent: 'flex-start', flex: 1, padding: '0.25rem 0.4rem' }}
                  onClick={() => onFocus(t)}
                  disabled={!t.estimatedPoint}
                  title={t.estimatedPoint ? 'Fokuskan peta ke titik perkiraan' : 'Titik tak bisa dipetakan'}
                >
                  <Text as="span" className="tnum" weight="semibold">{formatLength(t.distanceMeters)}</Text>
                  <span className="badge" style={{ marginLeft: '0.4rem' }}>{OTDR_EVENT_LABEL[t.eventType]}</span>
                  <span className="muted" style={{ marginLeft: '0.4rem' }}>
                    dari {t.measuredFrom === 'FROM' ? cable.fromKind : cable.toKind}
                  </span>
                  {t.beyondCable && (
                    <span className="badge" style={{ marginLeft: '0.4rem', color: WHATIF_COLOR, borderColor: WHATIF_COLOR }}>
                      di luar
                    </span>
                  )}
                </Button>
                {canRecord && (
                  <Button variant="subtle" icon={<IconClose size={14} />} onClick={() => onDelete(t.id)} aria-label="Hapus uji" />
                )}
              </div>
              {t.placement && <OtdrPlacementLine placement={t.placement} />}
            </div>
          ))}
        </div>
      )}

      {tests === null && <Text as="p" className="muted" size={200} style={{ margin: 0 }}>Memuat…</Text>}
      {tests !== null && list.length === 0 && !canRecord && (
        <Text as="p" className="muted" size={200} style={{ margin: 0 }}>Belum ada uji OTDR.</Text>
      )}

      {canRecord && (
        <div className="stack" style={{ gap: '0.4rem' }}>
          <div className="row" style={{ gap: '0.4rem' }}>
            <TextField
              label="Jarak serat (m)"
              type="number"
              min={0}
              step="0.1"
              value={distance}
              onChange={(_, data) => setDistance(data.value)}
              placeholder="mis. 320"
              style={{ flex: 1 }}
            />
            <SelectField
              label="Diukur dari"
              value={measuredFrom}
              onChange={(_, data) => setMeasuredFrom(data.value as CableEnd)}
              style={{ width: '8.5rem' }}
            >
              <option value="FROM">Hulu ({cable.fromKind})</option>
              <option value="TO">Hilir ({cable.toKind})</option>
            </SelectField>
          </div>
          <div className="row" style={{ gap: '0.4rem' }}>
            <SelectField
              label="Peristiwa"
              value={eventType}
              onChange={(_, data) => setEventType(data.value as OtdrEventType)}
              style={{ flex: 1 }}
            >
              {OTDR_EVENT_OPTIONS.map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </SelectField>
            <TextField
              label="Redaman (dB)"
              type="number"
              min={0}
              step="0.1"
              value={lossDb}
              onChange={(_, data) => setLossDb(data.value)}
              placeholder="opsional"
              style={{ width: '8.5rem' }}
            />
          </div>
          <TextField
            label="Catatan (opsional)"
            value={note}
            onChange={(_, data) => setNote(data.value)}
            placeholder="mis. dekat tiang 12"
          />
          <Button variant="primary" disabled={!canSubmit} onClick={submit}>
            Plot &amp; simpan
          </Button>
        </div>
      )}
    </div>
  )
}

/**
 * Terjemahan angka OTDR jadi tempat: "jatuh di JB-03", "di antara JB-03 dan ODP-05".
 *
 * Ini baris yang sebenarnya dicari orang. Jarak dalam meter cuma bisa dipakai
 * setelah dicocokkan ke benda, dan pencocokan itu yang dulu dikerjakan di kepala
 * sambil menatap peta — sumber tebakan yang berujung menggali di ruas yang salah.
 * Saran tindakan ditampilkan hanya saat titiknya jatuh di kotak, sebab di situlah
 * ia mengubah keputusan: buka tutup closure, bukan panggil tim gali.
 */
function OtdrPlacementLine({ placement }: { placement: OtdrPlacement }) {
  return (
    <div className="stack" style={{ gap: '0.1rem', padding: '0 0.4rem 0.1rem' }}>
      <Text as="p" className="muted" size={100} style={{ ...typographyStyles.caption1, margin: 0 }}>
        {placement.atClosure && (
          <span
            className="badge"
            style={{ marginRight: '0.3rem', color: 'var(--warning)', borderColor: 'var(--warning)' }}
          >
            di kotak
          </span>
        )}
        {placement.summary}
      </Text>
      {placement.advice && (
        <Text as="p" className="dim" size={100} style={{ ...typographyStyles.caption2, margin: 0 }}>
          {placement.advice}
        </Text>
      )}
    </div>
  )
}

/**
 * Penggaris kabel: kotak-kotak yang seratnya benar-benar dibuka di sepanjang
 * bentang, urut dari pangkal beserta jaraknya.
 *
 * Bukan daftar aset di sekitar jalur — yang dekat tapi disuapi kabel lain cuma
 * tetangga, dan menyebutnya sebagai patokan justru menyesatkan. Ditampilkan
 * sekali di atas daftar uji supaya tiap hasil bisa dicocokkan tanpa pindah layar.
 */
function OtdrLandmarkRuler({ landmarks }: { landmarks: OtdrLandmark[] }) {
  return (
    <div className="row wrap" style={{ gap: '0.25rem' }}>
      <Text as="span" className="dim" size={100}>Patokan:</Text>
      {landmarks.map((l, index) => (
        <span key={l.closureId} className="row" style={{ gap: '0.25rem' }}>
          {index > 0 && <Text as="span" className="dim" size={100} aria-hidden>→</Text>}
          <Text as="span" className="badge" size={100} title={`${l.name} · ${formatLength(l.distanceMeters)} dari pangkal`}>
            {l.code}
            {!l.endpoint && <span className="dim"> {formatLength(l.distanceMeters)}</span>}
          </Text>
        </span>
      ))}
    </div>
  )
}
