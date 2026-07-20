import { useEffect, useRef, useState } from 'react'
import maplibregl, { type Map as MapLibreMap } from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { api, ApiError, tokenStore } from '../api/client'
import type { OdpInspection } from '../api/network'
import { useCan } from '../auth/useCan'

/**
 * Peta jaringan berbasis vector tile.
 *
 * Tile dirender PostGIS (`ST_AsMVT`) dan diambil per ubin, sehingga jumlah aset
 * yang tergambar tidak membebani browser — inilah yang membuat peta tetap ringan
 * di puluhan ribu titik. Klik sebuah ODP untuk melihat siapa yang tersambung.
 */

const OSM_ATTRIBUTION = '&copy; Kontributor OpenStreetMap'

/** Pusat awal: Bekasi, sekadar titik berangkat sebelum data pertama masuk. */
const INITIAL_CENTER: [number, number] = [106.995, -6.243]

const HEALTH_COLOR: Record<string, string> = {
  GOOD: '#22c55e',
  WARNING: '#f59e0b',
  CRITICAL: '#ef4444',
  UNKNOWN: '#64748b',
}

export function MapPage() {
  const container = useRef<HTMLDivElement>(null)
  const map = useRef<MapLibreMap | null>(null)
  const [selected, setSelected] = useState<OdpInspection | null>(null)
  const [error, setError] = useState<string | null>(null)
  const { can } = useCan()

  useEffect(() => {
    if (!container.current || map.current) return

    const instance = new maplibregl.Map({
      container: container.current,
      center: INITIAL_CENTER,
      zoom: 14,
      // Basemap raster OSM: cukup untuk pengembangan. Untuk produksi ganti ke
      // penyedia tile berlangganan atau server tile sendiri — kebijakan pemakaian
      // OSM tidak mengizinkan trafik aplikasi komersial.
      style: {
        version: 8,
        sources: {
          basemap: {
            type: 'raster',
            tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
            tileSize: 256,
            attribution: OSM_ATTRIBUTION,
          },
          ftth: {
            type: 'vector',
            tiles: [`${window.location.origin}/api/gis/tiles/{z}/{x}/{y}.mvt`],
            minzoom: 0,
            maxzoom: 22,
          },
        },
        layers: [
          { id: 'basemap', type: 'raster', source: 'basemap' },
          {
            id: 'cable',
            type: 'line',
            source: 'ftth',
            'source-layer': 'cable',
            paint: {
              'line-width': 2.5,
              'line-color': [
                'match',
                ['get', 'cable_type'],
                'FEEDER', '#a855f7',
                'DISTRIBUTION', '#3b82f6',
                'DROP', '#94a3b8',
                '#64748b',
              ],
            },
          },
          {
            id: 'customer',
            type: 'circle',
            source: 'ftth',
            'source-layer': 'customer',
            paint: {
              'circle-radius': 4,
              'circle-color': ['match', ['get', 'onu_status'], 'ONLINE', '#22c55e', 'LOS', '#ef4444', '#94a3b8'],
              'circle-stroke-width': 1,
              'circle-stroke-color': '#0f172a',
            },
          },
          {
            id: 'odp',
            type: 'circle',
            source: 'ftth',
            'source-layer': 'odp',
            paint: {
              'circle-radius': 7,
              'circle-color': ['match', ['get', 'status'], 'ACTIVE', '#f59e0b', 'PLANNED', '#64748b', '#78716c'],
              'circle-stroke-width': 2,
              'circle-stroke-color': '#fff',
            },
          },
          {
            id: 'odc',
            type: 'circle',
            source: 'ftth',
            'source-layer': 'odc',
            paint: {
              'circle-radius': 9,
              'circle-color': '#3b82f6',
              'circle-stroke-width': 2,
              'circle-stroke-color': '#fff',
            },
          },
          {
            id: 'site',
            type: 'circle',
            source: 'ftth',
            'source-layer': 'site',
            paint: {
              'circle-radius': 11,
              'circle-color': '#a855f7',
              'circle-stroke-width': 2,
              'circle-stroke-color': '#fff',
            },
          },
          {
            id: 'odp-label',
            type: 'symbol',
            source: 'ftth',
            'source-layer': 'odp',
            minzoom: 15,
            layout: { 'text-field': ['get', 'code'], 'text-size': 11, 'text-offset': [0, 1.4] },
            paint: { 'text-color': '#e2e8f0', 'text-halo-color': '#0f172a', 'text-halo-width': 1.5 },
          },
        ],
      },
      // Endpoint tile ikut dilindungi RBAC, jadi tokennya harus dibawa. MapLibre
      // mengambil tile sendiri sehingga klien HTTP biasa tidak terlibat.
      transformRequest: (url) => {
        if (!url.startsWith(`${window.location.origin}/api/`)) return { url }
        const token = tokenStore.getAccessToken()
        return { url, headers: token ? { Authorization: `Bearer ${token}` } : {} }
      },
    })

    instance.addControl(new maplibregl.NavigationControl(), 'top-right')
    instance.addControl(new maplibregl.ScaleControl(), 'bottom-left')

    instance.on('click', 'odp', (event) => {
      const feature = event.features?.[0]
      const id = feature?.properties?.id as string | undefined
      if (!id) return
      api
        .get<OdpInspection>(`/api/gis/odps/${id}`)
        .then(setSelected)
        .catch((err) => setError(err instanceof ApiError ? err.message : 'Gagal memuat detail ODP'))
    })
    instance.on('mouseenter', 'odp', () => {
      instance.getCanvas().style.cursor = 'pointer'
    })
    instance.on('mouseleave', 'odp', () => {
      instance.getCanvas().style.cursor = ''
    })

    map.current = instance
    return () => {
      instance.remove()
      map.current = null
    }
  }, [])

  if (!can('gis.map.view')) {
    return (
      <div className="card">
        <h3 style={{ marginTop: 0 }}>Akses ditolak</h3>
        <p className="muted">
          Kamu tidak punya izin <span className="badge">gis.map.view</span>.
        </p>
      </div>
    )
  }

  return (
    <div className="stack">
      <div className="spread">
        <h2 style={{ margin: 0 }}>Peta Jaringan</h2>
        <Legend />
      </div>
      {error && <p className="error">{error}</p>}
      <div className="map-shell">
        <div ref={container} className="map-canvas" />
        {selected && <OdpPanel inspection={selected} onClose={() => setSelected(null)} />}
      </div>
    </div>
  )
}

function Legend() {
  const items: Array<[string, string]> = [
    ['#a855f7', 'Site/POP'],
    ['#3b82f6', 'ODC'],
    ['#f59e0b', 'ODP'],
    ['#22c55e', 'Pelanggan'],
  ]
  return (
    <div className="row" style={{ flexWrap: 'wrap', gap: '0.75rem' }}>
      {items.map(([color, label]) => (
        <span key={label} className="row" style={{ gap: '0.35rem', alignItems: 'center' }}>
          <span style={{ width: 10, height: 10, borderRadius: '50%', background: color, display: 'inline-block' }} />
          <span className="muted" style={{ fontSize: '0.85rem' }}>
            {label}
          </span>
        </span>
      ))}
    </div>
  )
}

/** Panel jawaban atas pertanyaan lapangan: "di ODP ini ada siapa saja, port mana yang kosong?" */
function OdpPanel({ inspection, onClose }: { inspection: OdpInspection; onClose: () => void }) {
  const { upstream } = inspection
  return (
    <aside className="map-panel stack">
      <div className="spread">
        <h3 style={{ margin: 0 }}>{inspection.code}</h3>
        <button onClick={onClose}>Tutup</button>
      </div>
      <p className="muted" style={{ margin: 0 }}>
        {inspection.name}
      </p>

      <div>
        <div className="spread" style={{ marginBottom: '0.35rem' }}>
          <strong>
            {inspection.usedPorts}/{inspection.capacity} port terpakai
          </strong>
          <span className="badge">{inspection.utilizationPercent}%</span>
        </div>
        <div className="meter">
          <div className="meter-fill" style={{ width: `${inspection.utilizationPercent}%` }} />
        </div>
        <p className="muted" style={{ margin: '0.4rem 0 0', fontSize: '0.85rem' }}>
          Port kosong: {inspection.availablePortNumbers.join(', ') || '—'}
        </p>
      </div>

      <div>
        <strong>Jalur hulu</strong>
        <p className="muted" style={{ margin: '0.25rem 0 0', fontSize: '0.85rem', lineHeight: 1.6 }}>
          ODC {upstream.odcCode ?? '—'} → PON {upstream.ponPortLabel ?? '—'} → OLT {upstream.oltCode ?? '—'} → site{' '}
          {upstream.siteCode ?? '—'}
          <br />
          Rugi splitter {upstream.splitterLossDb.toFixed(1)} dB{' '}
          {!upstream.complete && <span className="badge">jalur belum lengkap</span>}
        </p>
      </div>

      <div>
        <strong>Pelanggan ({inspection.occupants.length})</strong>
        {inspection.occupants.length === 0 ? (
          <p className="muted" style={{ margin: '0.25rem 0 0' }}>
            Belum ada pelanggan tersambung.
          </p>
        ) : (
          <table style={{ marginTop: '0.5rem' }}>
            <thead>
              <tr>
                <th>Port</th>
                <th>Pelanggan</th>
                <th>ONU</th>
                <th>Optik</th>
              </tr>
            </thead>
            <tbody>
              {inspection.occupants.map((occupant) => (
                <tr key={occupant.portNumber}>
                  <td>{occupant.portNumber}</td>
                  <td>
                    {occupant.customerName}
                    <br />
                    <span className="muted" style={{ fontSize: '0.8rem' }}>
                      {occupant.phone ?? occupant.customerCode}
                    </span>
                  </td>
                  <td>
                    <span className="muted" style={{ fontSize: '0.8rem' }}>
                      {occupant.onuSerialNumber}
                    </span>
                    <br />
                    <span className="badge">{occupant.onuStatus}</span>
                  </td>
                  <td>
                    <span style={{ color: HEALTH_COLOR[occupant.opticalHealth] }}>
                      {occupant.installRxPowerDbm != null ? `${occupant.installRxPowerDbm} dBm` : occupant.opticalHealth}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </aside>
  )
}
