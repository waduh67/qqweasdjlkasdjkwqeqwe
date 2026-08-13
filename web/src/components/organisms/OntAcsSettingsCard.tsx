import { useEffect, useState } from 'react'
import { Copy } from 'lucide-react'
import { getAcsServerInfo, type AcsServerInfoView } from '@/api/acs'
import { useCan } from '@/auth/useCan'
import { Button, Spinner } from '@/components/atoms'
import { useToast } from '@/system'
import { copyText } from '@/utils/clipboard'

/**
 * Kartu "Setelan ONT" — daftar nilai TR-069 yang harus diketik operator/teknisi ke
 * halaman ACS di ONT pelanggan (ACS URL, kredensial, connection request, dan interval
 * inform). Nilainya global, berasal dari env deploy platform, jadi sama untuk semua
 * pelanggan; kartu ini muncul di halaman `/acs` maupun di tab Ringkasan pelanggan
 * tepat setelah operator mendaftarkan serial ONU.
 *
 * Yang paling sering salah di lapangan adalah **Periodic Inform Interval**: bawaan
 * pabrik ONT umumnya 3600 detik, sedangkan sinkronisasi konsol ini menganggap
 * perangkat basi jauh lebih cepat. Karenanya barisnya diberi catatan sendiri.
 *
 * Mengambil datanya sendiri (pemanggil cukup memasang komponennya) dan meng-cache
 * promise-nya di tingkat modul: muatannya konstanta env yang tak mungkin berubah
 * dalam satu sesi, jadi berpindah antar pelanggan tak perlu memanggil ulang.
 *
 * Render `null` bila pengguna tak punya `cpe.acs.view`, jadi pemanggil tak perlu
 * memagari sendiri.
 */
let cached: Promise<AcsServerInfoView> | null = null
const load = () => (cached ??= getAcsServerInfo())

export function OntAcsSettingsCard() {
  const { can } = useCan()
  const toast = useToast()
  const canView = can('cpe.acs.view')
  const [info, setInfo] = useState<AcsServerInfoView | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    if (!canView) return
    let alive = true
    void load()
      .then((data) => alive && setInfo(data))
      .catch(() => {
        // Promise gagal jangan disimpan di cache — kalau tidak, satu kegagalan
        // sesaat membuat kartunya kosong selamanya sampai halaman di-reload.
        cached = null
        if (alive) setFailed(true)
      })
    return () => {
      alive = false
    }
  }, [canView])

  if (!canView) return null

  if (failed) {
    return (
      <div className="card">
        <h3 style={{ marginTop: 0 }}>Setelan ONT (TR-069)</h3>
        <p className="muted" style={{ margin: 0 }}>Gagal memuat setelan server ACS.</p>
      </div>
    )
  }

  if (!info) {
    return (
      <div className="card">
        <h3 style={{ marginTop: 0 }}>Setelan ONT (TR-069)</h3>
        <Spinner />
      </div>
    )
  }

  const rows = settingRows(info)

  const copyOne = async (label: string, value: string) => {
    if (await copyText(value)) toast.success(`${label} disalin`)
    else toast.error('Browser menolak akses papan klip — salin manual')
  }

  return (
    <div className="card stack" style={{ gap: '0.9rem' }}>
      <div className="spread" style={{ alignItems: 'flex-start', gap: '0.75rem' }}>
        <div className="stack" style={{ gap: '0.2rem' }}>
          <h3 style={{ margin: 0 }}>Setelan ONT (TR-069)</h3>
          <span className="muted" style={{ fontSize: '0.82rem' }}>
            Ketik nilai ini di halaman ACS pada ONT pelanggan. Sama untuk semua perangkat.
          </span>
        </div>
        <Button
          type="button"
          size="small"
          onClick={() => void copyAll(rows, toast)}
          disabled={!info.configured}
        >
          Salin semua
        </Button>
      </div>

      {!info.configured && (
        <p
          className="muted"
          style={{ margin: 0, fontSize: '0.82rem', fontWeight: 600, color: 'var(--warning)' }}
        >
          Alamat CWMP belum dikonfigurasi platform (FTTH_CPE_PUBLIC_HOST kosong). Hubungi
          admin platform sebelum menyetel ONT — tanpa alamat itu perangkat tak bisa
          menemukan server ACS.
        </p>
      )}

      <div className="stack" style={{ gap: '0.55rem' }}>
        {rows.map((row) => (
          <SettingRow key={row.label} row={row} onCopy={copyOne} />
        ))}
      </div>
    </div>
  )
}

/** Satu baris setelan; [value] null berarti platform sengaja mengosongkannya. */
interface SettingRow {
  label: string
  value: string | null
  note?: string
}

/** Susun baris sesuai urutan pengisian di halaman ACS milik ONT. */
function settingRows(info: AcsServerInfoView): SettingRow[] {
  return [
    { label: 'ACS URL', value: info.cwmpUrl },
    { label: 'ACS Username', value: info.acsUsername },
    { label: 'ACS Password', value: info.acsPassword },
    { label: 'Connection Request Username', value: info.connectionRequestUsername },
    { label: 'Connection Request Password', value: info.connectionRequestPassword },
    {
      label: 'Periodic Inform',
      value: info.periodicInformEnabled ? 'Aktif' : 'Nonaktif',
    },
    {
      label: 'Periodic Inform Interval',
      value: `${info.periodicInformIntervalSeconds}`,
      note: `detik — bawaan pabrik ONT biasanya 3600, ganti jadi ${info.periodicInformIntervalSeconds}`,
    },
  ]
}

/** Label + nilai monospace + tombol salin per baris. */
function SettingRow({
  row,
  onCopy,
}: {
  row: SettingRow
  onCopy: (label: string, value: string) => void
}) {
  return (
    <div className="stack" style={{ gap: '0.2rem' }}>
      <span className="muted" style={{ fontSize: '0.76rem', fontWeight: 600 }}>{row.label}</span>
      <div className="row" style={{ gap: '0.4rem', alignItems: 'center' }}>
        {row.value ? (
          <code style={{ flex: 1, overflowX: 'auto', padding: '0.3rem 0.5rem', fontSize: '0.82rem' }}>
            {row.value}
          </code>
        ) : (
          <span className="muted" style={{ flex: 1, fontSize: '0.82rem', fontStyle: 'italic' }}>
            kosongkan
          </span>
        )}
        {row.value && (
          <Button
            type="button"
            size="small"
            variant="subtle"
            icon={<Copy size={14} />}
            aria-label={`Salin ${row.label}`}
            title={`Salin ${row.label}`}
            onClick={() => onCopy(row.label, row.value as string)}
          />
        )}
      </div>
      {row.note && (
        <span className="muted" style={{ fontSize: '0.74rem' }}>{row.note}</span>
      )}
    </div>
  )
}

/**
 * Salin seluruh setelan sebagai satu blok berlabel — bentuk inilah yang benar-benar
 * ditempel teknisi ke grup WhatsApp tim lapangan.
 */
async function copyAll(rows: SettingRow[], toast: ReturnType<typeof useToast>) {
  const block = ['Setelan ACS (TR-069) untuk ONT:', ...rows.map((r) => `${r.label}: ${r.value ?? '(kosongkan)'}`)].join(
    '\n',
  )
  if (await copyText(block)) toast.success('Setelan ONT disalin')
  else toast.error('Browser menolak akses papan klip — salin manual')
}
