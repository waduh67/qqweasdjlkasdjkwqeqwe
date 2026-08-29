import { useState } from 'react'
import { typographyStyles } from '@fluentui/react-components'
import { api, ApiError } from '@/api/client'
import type { OidCheck, OidVerdict, OltSnmpCheck, OltSnmpWalk } from '@/api/monitoring'
import { Badge, Button, EmptyState, Spinner, TextField, type Tone } from '@/components/atoms'
import { IconInventory } from '@/components/atoms/icons'
import { useToast } from '@/system'

/**
 * Alat validasi OID di lapangan — untuk teknisi yang sedang berdiri di depan OLT.
 *
 * Peta MIB kami disusun dari dokumentasi vendor, dan firmware yang berbeda kerap
 * menggeser sub-tree-nya. Bila satu OID meleset, polling TIDAK melempar error: ia diam
 * mengembalikan nol baris, dan OLT-nya tampak "sehat tapi tak punya ONU". Panel ini
 * menyuruh server menanyai perangkatnya langsung lalu menunjukkan OID mana yang menjawab,
 * mana yang kosong, dan mana yang menjawab tapi nilainya tak terbaca aturan kami.
 *
 * Yang ditampilkan sengaja termasuk NILAI MENTAH: dari situlah operator menebak skala yang
 * benar (`-2350` = 0,01 dBm, `-23500` = 0,001 dBm) tanpa perlu snmpwalk maupun akses shell.
 * Community string tak pernah ikut ke browser — server yang memegangnya.
 */

const VERDICT: Record<OidVerdict, { label: string; tone: Tone }> = {
  OK: { label: 'Terbaca', tone: 'good' },
  EMPTY: { label: 'Kosong', tone: 'warning' },
  UNREADABLE: { label: 'Tak terbaca', tone: 'critical' },
  NOT_CONFIGURED: { label: 'Belum dipetakan', tone: 'neutral' },
}

export function SnmpDiagnosticPanel({ oltId }: { oltId: string }) {
  const toast = useToast()
  const [check, setCheck] = useState<OltSnmpCheck | null>(null)
  const [running, setRunning] = useState(false)

  const run = async () => {
    setRunning(true)
    try {
      setCheck(await api.get<OltSnmpCheck>(`/api/monitoring/olts/${oltId}/snmp-check`))
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menguji OID')
    } finally {
      setRunning(false)
    }
  }

  const failing = check?.oids.filter((o) => o.essential && o.verdict !== 'OK') ?? []

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <div className="card stack">
        <div className="spread" style={{ gap: '0.75rem', alignItems: 'center', flexWrap: 'wrap' }}>
          <h3 style={{ margin: 0 }}>Uji peta OID</h3>
          <Button variant="primary" disabled={running} onClick={() => void run()}>
            {running ? 'Menanyai perangkat…' : check ? 'Uji ulang' : 'Jalankan uji'}
          </Button>
        </div>
        <p className="muted" style={{ margin: 0, ...typographyStyles.body1 }}>
          Server menanyai OLT ini memakai OID yang persis dipakai polling, lalu menunjukkan mana yang
          menjawab. Jalankan saat OLT baru dipasang, setelah firmware naik, atau ketika OLT tampak sehat
          tapi tak punya ONU satu pun.
        </p>

        {running && !check && (
          <div style={{ display: 'grid', placeItems: 'center', padding: '1.5rem' }}>
            <Spinner />
          </div>
        )}

        {check && (
          <>
            <div className="row wrap" style={{ gap: '0.5rem', alignItems: 'center' }}>
              <Badge>{check.vendor}</Badge>
              {check.reachable ? <Badge tone="good">Menjawab</Badge> : <Badge tone="critical">Tak menjawab</Badge>}
              {!check.supported && <Badge tone="warning">Vendor belum didukung</Badge>}
              {check.roundTripMillis != null && <Badge tone="neutral">{check.roundTripMillis} ms</Badge>}
              {failing.length > 0 && <Badge tone="critical">{failing.length} OID wajib bermasalah</Badge>}
              {check.supported && check.reachable && failing.length === 0 && check.oids.length > 0 && (
                <Badge tone="good">Semua OID wajib terbaca</Badge>
              )}
            </div>

            {check.systemDescription && (
              <p className="muted" style={{ margin: 0, ...typographyStyles.body1, wordBreak: 'break-word' }}>
                <strong>sysDescr:</strong> {check.systemDescription}
              </p>
            )}
            {check.failureReason && (
              <p style={{ margin: 0, ...typographyStyles.body1, color: 'var(--critical-ink)' }}>{check.failureReason}</p>
            )}

            {check.oids.length > 0 && (
              <div className="stack" style={{ gap: 0 }}>
                {check.oids.map((o) => (
                  <OidRow key={o.role} check={o} />
                ))}
              </div>
            )}
          </>
        )}

        {!check && !running && (
          <EmptyState
            title="Belum diuji"
            hint="Uji hanya berjalan saat diminta — walk SNMP membebani CPU manajemen OLT."
            icon={<IconInventory size={30} />}
          />
        )}
      </div>

      <ManualWalkCard oltId={oltId} />
    </div>
  )
}

/** Satu peran OID: vonis, contoh nilai mentah beserta tafsirannya, dan saran tindak lanjut. */
function OidRow({ check }: { check: OidCheck }) {
  const verdict = VERDICT[check.verdict]
  return (
    <div className="stack" style={{ gap: '0.3rem', padding: '0.6rem 0', borderTop: '1px solid var(--border)' }}>
      <div className="spread" style={{ gap: '0.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
        <span className="row" style={{ gap: '0.45rem', alignItems: 'center', minWidth: 0 }}>
          <strong style={{ ...typographyStyles.subtitle2 }}>{check.label}</strong>
          {check.essential && <Badge tone="neutral">wajib</Badge>}
        </span>
        <span className="row" style={{ gap: '0.4rem', alignItems: 'center', flexShrink: 0 }}>
          {check.sampleCount > 0 && (
            <span className="muted tnum" style={{ ...typographyStyles.caption1 }}>
              {check.sampleCount} nilai
            </span>
          )}
          <Badge tone={verdict.tone}>{verdict.label}</Badge>
        </span>
      </div>

      <code className="tnum" style={{ ...typographyStyles.caption1, color: 'var(--text-3)', wordBreak: 'break-all' }}>
        {check.oid ?? '— belum ada OID —'}
      </code>

      {check.samples.length > 0 && (
        <div className="stack" style={{ gap: '0.15rem' }}>
          {check.samples.map((s) => (
            <div key={s.index} className="row tnum" style={{ gap: '0.4rem', ...typographyStyles.caption1, flexWrap: 'wrap' }}>
              <span className="muted">.{s.index}</span>
              <span style={{ wordBreak: 'break-all' }}>{s.raw}</span>
              <span aria-hidden className="muted">
                →
              </span>
              <span style={{ color: s.interpreted ? 'var(--good-ink)' : 'var(--critical-ink)' }}>
                {s.interpreted ?? 'tak terbaca'}
              </span>
            </div>
          ))}
        </div>
      )}

      {check.hint && (
        <p className="muted" style={{ margin: 0, ...typographyStyles.caption1 }}>
          {check.hint}
        </p>
      )}
    </div>
  )
}

/**
 * Walk OID bebas: senjata untuk MENCARI OID yang benar setelah uji di atas menunjukkan
 * yang lama meleset. Sasarannya selalu OLT ini (server yang memegang alamat & community),
 * dan server menolak OID yang terlalu umum agar walk tak menyapu seluruh perangkat.
 */
function ManualWalkCard({ oltId }: { oltId: string }) {
  const toast = useToast()
  const [oid, setOid] = useState('')
  const [result, setResult] = useState<OltSnmpWalk | null>(null)
  const [running, setRunning] = useState(false)

  const walk = async () => {
    const trimmed = oid.trim()
    if (!trimmed) return
    setRunning(true)
    try {
      setResult(
        await api.get<OltSnmpWalk>(
          `/api/monitoring/olts/${oltId}/snmp-walk?oid=${encodeURIComponent(trimmed)}&limit=50`,
        ),
      )
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Walk OID gagal')
    } finally {
      setRunning(false)
    }
  }

  return (
    <div className="card stack">
      <h3 style={{ margin: 0 }}>Walk OID manual</h3>
      <p className="muted" style={{ margin: 0, ...typographyStyles.body1 }}>
        Telusuri sub-tree OID di OLT ini untuk menemukan OID yang benar saat profil vendor meleset.
        Sebutkan OID yang cukup spesifik (mis. <code>1.3.6.1.4.1.50224.3.3.2.1.7</code>) — walk dari akar
        bisa berjalan belasan menit dan membebani perangkat produksi.
      </p>
      <div className="row wrap" style={{ gap: '0.4rem', alignItems: 'flex-end' }}>
        <TextField
          label="OID"
          value={oid}
          onChange={(_, data) => setOid(data.value)}
          placeholder="1.3.6.1.4.1.50224.3.3.2.1.7"
          style={{ flex: 1, minWidth: '14rem' }}
        />
        <Button variant="primary" disabled={running || !oid.trim()} onClick={() => void walk()}>
          {running ? 'Men-walk…' : 'Walk'}
        </Button>
      </div>

      {result && (
        <>
          <div className="row wrap" style={{ gap: '0.5rem', alignItems: 'center' }}>
            <Badge tone={result.sampleCount > 0 ? 'good' : 'warning'}>{result.sampleCount} nilai</Badge>
            <Badge tone="neutral">{result.elapsedMillis} ms</Badge>
            {result.truncated && <Badge tone="warning">dipotong {result.rows.length} teratas</Badge>}
          </div>
          {result.rows.length === 0 ? (
            <p className="muted" style={{ margin: 0, ...typographyStyles.body1 }}>
              Sub-tree ini kosong di perangkat tersebut. Kalau OID-nya kamu ketik lengkap dengan indeks
              (mis. berakhiran <code>.0</code>), coba tanpa indeksnya — walk menelusuri ANAK sebuah OID,
              bukan nilai OID itu sendiri.
            </p>
          ) : (
            <div className="stack" style={{ gap: 0 }}>
              {result.rows.map((r) => (
                <div
                  key={r.oid}
                  className="row tnum"
                  style={{
                    gap: '0.5rem',
                    ...typographyStyles.caption1,
                    padding: '0.25rem 0',
                    borderTop: '1px solid var(--border)',
                    flexWrap: 'wrap',
                  }}
                >
                  <span className="muted" style={{ wordBreak: 'break-all' }}>
                    {r.oid}
                  </span>
                  <span style={{ wordBreak: 'break-all' }}>{r.value}</span>
                </div>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  )
}
