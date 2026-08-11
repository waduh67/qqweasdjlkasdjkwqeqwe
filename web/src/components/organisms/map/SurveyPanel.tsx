import { MessageBar, MessageBarBody } from '@fluentui/react-components'
import type { SurveyCapacityView, SurveyOdp } from '@/api/network'
import { BladeHead } from '@/components/molecules'
import { TYPE_LABEL, formatLength } from '@/map/cableFormat'

/**
 * Panel cek kapasitas untuk survey.
 *
 * Susunannya mengikuti urutan orang mengambil keputusan di lapangan, bukan
 * urutan data di server: kalimat kesimpulan dulu (itu yang diucapkan ke calon
 * pelanggan), lalu kotak yang siap pakai, baru selubung yang lewat sebagai jalan
 * keluar kalau semua kotak penuh. Angka detail — sisa port, sisa kaki splitter,
 * nomor core kosong — ada di bawahnya untuk yang mau memeriksa.
 */
export function SurveyPanel({
  survey,
  onOpenOdp,
  onClose,
}: {
  survey: SurveyCapacityView
  onOpenOdp: (row: SurveyOdp) => void
  onClose: () => void
}) {
  return (
    <aside className="map-panel blade">
      <BladeHead
        title="Cek kapasitas"
        subtitle={`${survey.location.latitude.toFixed(6)}, ${survey.location.longitude.toFixed(6)} · radius ${formatLength(survey.radiusMeters)}`}
        onClose={onClose}
      />

      <div className="blade-body stack" style={{ gap: '0.9rem' }}>
        <MessageBar intent={survey.serviceable ? 'success' : survey.cables.length > 0 ? 'warning' : 'error'}>
          <MessageBarBody>{survey.verdict}</MessageBarBody>
        </MessageBar>

        {survey.odps.length > 0 && (
          <div className="stack" style={{ gap: '0.45rem' }}>
            <p className="blade-section-title">Kotak dalam jangkauan ({survey.odps.length})</p>
            {survey.odps.map((o) => (
              <button
                key={o.odpId}
                type="button"
                className="card clickable"
                style={{ textAlign: 'left', width: '100%', padding: '0.55rem 0.7rem' }}
                onClick={() => onOpenOdp(o)}
              >
                <div className="spread" style={{ gap: '0.5rem' }}>
                  <span style={{ fontWeight: 600 }}>{o.code}</span>
                  <span className="tnum muted">{formatLength(o.distanceMeters)}</span>
                </div>
                <div className="row wrap" style={{ gap: '0.35rem', marginTop: '0.3rem' }}>
                  <span
                    className="badge"
                    style={{
                      color: o.ready ? 'var(--good-ink)' : 'var(--warning-ink)',
                      borderColor: o.ready ? 'var(--good-ink)' : 'var(--warning-ink)',
                    }}
                  >
                    {o.ready ? 'siap pakai' : 'belum bisa'}
                  </span>
                  <span className="muted tnum">
                    {o.freePorts}/{o.capacity} port kosong
                  </span>
                  {o.splitterLegs > 0 && (
                    <span className="muted tnum">
                      · {o.freeLegs}/{o.splitterLegs} kaki splitter
                    </span>
                  )}
                </div>
                {o.note && (
                  <p className="dim" style={{ margin: '0.3rem 0 0', fontSize: '0.72rem', lineHeight: 1.35 }}>
                    {o.note}
                  </p>
                )}
              </button>
            ))}
          </div>
        )}

        {survey.cables.length > 0 && (
          <div className="stack" style={{ gap: '0.45rem' }}>
            <p className="blade-section-title">Selubung yang lewat ({survey.cables.length})</p>
            {survey.cables.map((c) => (
              <div key={c.cableId} className="card" style={{ padding: '0.55rem 0.7rem' }}>
                <div className="spread" style={{ gap: '0.5rem' }}>
                  <span style={{ fontWeight: 600 }}>{c.code}</span>
                  <span className="tnum muted">{formatLength(c.distanceMeters)}</span>
                </div>
                <div className="row wrap" style={{ gap: '0.35rem', marginTop: '0.3rem' }}>
                  <span className="badge">{TYPE_LABEL[c.cableType]}</span>
                  <span className="muted tnum">
                    {c.freeCores}/{c.coreCount} core menganggur
                  </span>
                </div>
                <p className="dim" style={{ margin: '0.3rem 0 0', fontSize: '0.72rem', lineHeight: 1.35 }}>
                  Kupas di {formatLength(c.tapDistanceMeters)} dari ujung awal kabel · core kosong{' '}
                  {c.freeCoreNumbers.join(', ')}
                  {c.freeCores > c.freeCoreNumbers.length && ', …'}
                </p>
              </div>
            ))}
          </div>
        )}

        {survey.warnings.map((w) => (
          <p key={w} className="dim" style={{ margin: 0, fontSize: '0.72rem', lineHeight: 1.35 }}>
            {w}
          </p>
        ))}
      </div>
    </aside>
  )
}
