import type {
  GeoJSONSource,
  Map as MapLibreMap,
  MapMouseEvent,
  MapLayerMouseEvent,
} from 'maplibre-gl'

/**
 * Alat menggambar & mengedit jalur kabel di atas peta.
 *
 * Diletakkan terpisah dari komponen React karena interaksinya imperatif: ia
 * memasang handler MapLibre, memelihara beberapa sumber GeoJSON, dan memutakhirkan
 * geometri langsung tiap gerak kursor. Komponen React cukup menerima ringkasan
 * lewat `onChange` (dari mana → ke mana, panjang, tipe) untuk merender panel —
 * bukan mengurus state peta.
 *
 * Prinsip desain: ujung kabel SELALU menempel ke perangkat nyata (snap), bukan
 * koordinat lepas. Itulah yang menjaga grafik jaringan tetap tersambung — telusur
 * jalur dan "siapa terdampak kalau putus" bergantung padanya.
 */

export type NodeKind = 'SITE' | 'OLT' | 'ODC' | 'ODP' | 'CUSTOMER'
export type CableType = 'FEEDER' | 'DISTRIBUTION' | 'DROP'

export interface SnappedDevice {
  kind: NodeKind
  id: string
  code: string
  lng: number
  lat: number
}

/** Ringkasan yang dikirim ke React untuk merender panel. */
export interface ToolState {
  mode: 'idle' | 'draw' | 'edit'
  from: SnappedDevice | null
  to: SnappedDevice | null
  /** Jumlah titik belok (tidak termasuk kedua ujung). */
  bendCount: number
  lengthMeters: number
  cableType: CableType | null
  /** Pasangan ujung membentuk tipe kabel yang sah. */
  valid: boolean
  /** Draw: kedua ujung sudah ditentukan → siap disimpan. */
  complete: boolean
}

/** Kabel yang sedang diedit — rutenya penuh termasuk kedua ujung terkunci. */
export interface EditableCable {
  id: string
  code: string
  route: Array<{ longitude: number; latitude: number }>
  fromKind: NodeKind
  fromId: string
  toKind: NodeKind
  toId: string
  cableType: CableType
}

export interface CableTool {
  startDraw(): void
  startEdit(cable: EditableCable): void
  removeLastBend(): void
  cancel(): void
  destroy(): void
  /** Koordinat rute lengkap [lng,lat] termasuk kedua ujung — untuk disimpan. */
  route(): Array<[number, number]>
}

/** Lapisan perangkat yang bisa jadi sasaran snap. Urutan = prioritas saat bertumpuk. */
const DEVICE_LAYERS = ['customer', 'odp', 'odc', 'site']
const LAYER_KIND: Record<string, NodeKind> = { site: 'SITE', odc: 'ODC', odp: 'ODP', customer: 'CUSTOMER' }

/** Pasangan ujung yang sah beserta tipe kabel yang tersirat (cermin aturan server). */
function inferType(from: NodeKind, to: NodeKind): CableType | null {
  if ((from === 'SITE' || from === 'OLT') && to === 'ODC') return 'FEEDER'
  if ((from === 'ODC' || from === 'ODP') && to === 'ODP') return 'DISTRIBUTION'
  if (from === 'ODP' && to === 'CUSTOMER') return 'DROP'
  return null
}

/** Perangkat yang boleh jadi TITIK AWAL kabel. */
function canStartFrom(kind: NodeKind): boolean {
  return kind === 'SITE' || kind === 'OLT' || kind === 'ODC' || kind === 'ODP'
}

const EARTH_RADIUS_M = 6_371_008.8

function haversine(a: [number, number], b: [number, number]): number {
  const toRad = (x: number) => (x * Math.PI) / 180
  const dLat = toRad(b[1] - a[1])
  const dLng = toRad(b[0] - a[0])
  const s =
    Math.sin(dLat / 2) ** 2 + Math.cos(toRad(a[1])) * Math.cos(toRad(b[1])) * Math.sin(dLng / 2) ** 2
  return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(s))
}

function lineLength(coords: Array<[number, number]>): number {
  let total = 0
  for (let i = 1; i < coords.length; i++) total += haversine(coords[i - 1], coords[i])
  return total
}

const SRC_LINE = 'cable-tool-line'
const SRC_VERTS = 'cable-tool-verts'
const SRC_SNAP = 'cable-tool-snap'

export function createCableTool(map: MapLibreMap, onChange: (state: ToolState) => void): CableTool {
  let mode: ToolState['mode'] = 'idle'

  // --- state gambar ---
  let from: SnappedDevice | null = null
  let to: SnappedDevice | null = null
  let bends: Array<[number, number]> = [] // titik belok antara from dan to
  let cursor: [number, number] | null = null
  let snap: SnappedDevice | null = null

  // --- state edit ---
  let editEndpoints: { from: SnappedDevice; to: SnappedDevice } | null = null
  let editCoords: Array<[number, number]> = [] // rute penuh termasuk ujung; 0 & terakhir terkunci
  let dragIndex: number | null = null
  let dragMoved = false

  // ---------- sumber & lapisan ----------

  function ensureSourcesAndLayers() {
    const empty = { type: 'FeatureCollection' as const, features: [] }
    if (!map.getSource(SRC_LINE)) map.addSource(SRC_LINE, { type: 'geojson', data: empty })
    if (!map.getSource(SRC_VERTS)) map.addSource(SRC_VERTS, { type: 'geojson', data: empty })
    if (!map.getSource(SRC_SNAP)) map.addSource(SRC_SNAP, { type: 'geojson', data: empty })

    if (!map.getLayer('cable-tool-line-l')) {
      map.addLayer({
        id: 'cable-tool-line-l',
        type: 'line',
        source: SRC_LINE,
        paint: {
          'line-color': '#2a78d6',
          'line-width': 3,
          'line-dasharray': [2, 1.3],
        },
      })
    }
    // Jalur klik tak kasat mata yang jauh lebih lebar dari garis 3px, supaya
    // menyisipkan titik belok saat mengedit tidak menuntut ketepatan piksel.
    if (!map.getLayer('cable-tool-hit-l')) {
      map.addLayer({
        id: 'cable-tool-hit-l',
        type: 'line',
        source: SRC_LINE,
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': '#2a78d6', 'line-opacity': 0, 'line-width': 20 },
      })
    }
    // Cincin sorotan pada perangkat yang akan di-snap.
    if (!map.getLayer('cable-tool-snap-l')) {
      map.addLayer({
        id: 'cable-tool-snap-l',
        type: 'circle',
        source: SRC_SNAP,
        paint: {
          'circle-radius': 13,
          'circle-color': 'rgba(42,120,214,0.18)',
          'circle-stroke-color': '#2a78d6',
          'circle-stroke-width': 2,
        },
      })
    }
    // Titik belok: kotak putih; ujung terkunci lebih besar.
    if (!map.getLayer('cable-tool-verts-l')) {
      map.addLayer({
        id: 'cable-tool-verts-l',
        type: 'circle',
        source: SRC_VERTS,
        paint: {
          'circle-radius': ['case', ['get', 'locked'], 6, 5],
          'circle-color': ['case', ['get', 'locked'], '#2a78d6', '#ffffff'],
          'circle-stroke-color': '#2a78d6',
          'circle-stroke-width': 2,
        },
      })
    }
  }

  function removeLayersAndSources() {
    for (const id of ['cable-tool-hit-l', 'cable-tool-line-l', 'cable-tool-snap-l', 'cable-tool-verts-l']) {
      if (map.getLayer(id)) map.removeLayer(id)
    }
    for (const id of [SRC_LINE, SRC_VERTS, SRC_SNAP]) {
      if (map.getSource(id)) map.removeSource(id)
    }
  }

  function setLine(coords: Array<[number, number]>) {
    const src = map.getSource(SRC_LINE) as GeoJSONSource | undefined
    src?.setData(
      coords.length >= 2
        ? { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: coords } }
        : { type: 'FeatureCollection', features: [] },
    )
  }

  function setVerts(points: Array<{ coord: [number, number]; locked: boolean }>) {
    const src = map.getSource(SRC_VERTS) as GeoJSONSource | undefined
    src?.setData({
      type: 'FeatureCollection',
      // `index` disematkan sebagai properti (bukan cocokkan koordinat) karena
      // MapLibre mengkuantisasi koordinat sumber GeoJSON saat memetakannya ke tile
      // internal — `queryRenderedFeatures` mengembalikan angka yang SEDIKIT berbeda
      // dari yang kita masukkan, jadi pencocokan `===` selalu meleset dan seret titik
      // tak pernah aktif. Indeks eksplisit kebal terhadap kuantisasi itu.
      features: points.map((p, i) => ({
        type: 'Feature',
        properties: { locked: p.locked, index: i },
        geometry: { type: 'Point', coordinates: p.coord },
      })),
    })
  }

  function setSnapRing(device: SnappedDevice | null) {
    const src = map.getSource(SRC_SNAP) as GeoJSONSource | undefined
    src?.setData(
      device
        ? { type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: [device.lng, device.lat] } }
        : { type: 'FeatureCollection', features: [] },
    )
  }

  // ---------- snapping ----------

  function deviceAt(point: { x: number; y: number }): SnappedDevice | null {
    const layers = DEVICE_LAYERS.filter((l) => map.getLayer(l))
    if (layers.length === 0) return null
    const r = 10
    const hits = map.queryRenderedFeatures(
      [
        [point.x - r, point.y - r],
        [point.x + r, point.y + r],
      ],
      { layers },
    )
    const hit = hits[0]
    if (!hit || hit.geometry.type !== 'Point') return null
    const [lng, lat] = hit.geometry.coordinates as [number, number]
    return {
      kind: LAYER_KIND[hit.layer.id] ?? 'ODP',
      id: String(hit.properties?.id ?? ''),
      code: String(hit.properties?.code ?? ''),
      lng,
      lat,
    }
  }

  // ---------- ringkasan ke React ----------

  function currentRoute(): Array<[number, number]> {
    if (mode === 'edit') return editCoords
    const coords: Array<[number, number]> = []
    if (from) coords.push([from.lng, from.lat])
    coords.push(...bends)
    if (to) coords.push([to.lng, to.lat])
    return coords
  }

  function emit() {
    const type =
      mode === 'edit' && editEndpoints
        ? inferType(editEndpoints.from.kind, editEndpoints.to.kind)
        : from && to
          ? inferType(from.kind, to.kind)
          : from && snap && inferType(from.kind, snap.kind)
    onChange({
      mode,
      from: mode === 'edit' ? editEndpoints?.from ?? null : from,
      to: mode === 'edit' ? editEndpoints?.to ?? null : to,
      bendCount: mode === 'edit' ? Math.max(0, editCoords.length - 2) : bends.length,
      lengthMeters: lineLength(currentRoute()),
      cableType: type ?? null,
      valid: mode === 'edit' ? true : from != null && to != null && inferType(from.kind, to.kind) != null,
      complete: mode === 'draw' && from != null && to != null,
    })
  }

  // ---------- render menurut state ----------

  function renderDraw() {
    const tail: [number, number] | null = snap ? [snap.lng, snap.lat] : cursor
    const coords: Array<[number, number]> = []
    if (from) coords.push([from.lng, from.lat])
    coords.push(...bends)
    if (!to && tail) coords.push(tail)
    if (to) coords.push([to.lng, to.lat])
    setLine(coords)

    const verts: Array<{ coord: [number, number]; locked: boolean }> = []
    if (from) verts.push({ coord: [from.lng, from.lat], locked: true })
    bends.forEach((b) => verts.push({ coord: b, locked: false }))
    if (to) verts.push({ coord: [to.lng, to.lat], locked: true })
    setVerts(verts)

    setSnapRing(snap)
  }

  function renderEdit() {
    setLine(editCoords)
    setVerts(
      editCoords.map((coord, i) => ({ coord, locked: i === 0 || i === editCoords.length - 1 })),
    )
    setSnapRing(null)
  }

  // ---------- handler: gambar ----------

  const onMove = (e: MapMouseEvent) => {
    if (mode !== 'draw') return
    cursor = [e.lngLat.lng, e.lngLat.lat]
    const candidate = deviceAt(e.point)
    // Saat mencari ujung awal, hanya perangkat yang boleh jadi awal yang disorot.
    // Saat mencari ujung akhir, hanya perangkat yang membentuk tipe kabel sah.
    if (!from) {
      snap = candidate && canStartFrom(candidate.kind) ? candidate : null
    } else {
      snap =
        candidate && candidate.id !== from.id && inferType(from.kind, candidate.kind) != null ? candidate : null
    }
    map.getCanvas().style.cursor = snap ? 'pointer' : 'crosshair'
    renderDraw()
    emit()
  }

  const onClickDraw = (e: MapMouseEvent) => {
    if (mode !== 'draw') return
    const candidate = deviceAt(e.point)
    if (!from) {
      if (candidate && canStartFrom(candidate.kind)) {
        from = candidate
        snap = null
      }
      renderDraw()
      emit()
      return
    }
    // Ujung sudah ada: klik perangkat sasaran yang sah = selesai.
    if (candidate && candidate.id !== from.id && inferType(from.kind, candidate.kind) != null) {
      to = candidate
      snap = null
      map.getCanvas().style.cursor = ''
      renderDraw()
      emit()
      return
    }
    // Selain itu: tambah titik belok di posisi klik.
    bends.push([e.lngLat.lng, e.lngLat.lat])
    renderDraw()
    emit()
  }

  // ---------- handler: edit (geser/tambah/hapus titik) ----------

  const onVertMouseDown = (e: MapLayerMouseEvent) => {
    if (mode !== 'edit') return
    const f = e.features?.[0]
    if (!f) return
    const idx = Number(f.properties?.index)
    if (!Number.isInteger(idx) || idx <= 0 || idx >= editCoords.length - 1) return // ujung terkunci
    dragIndex = idx
    dragMoved = false
    map.dragPan.disable()
    e.preventDefault()
  }

  const onEditMove = (e: MapMouseEvent) => {
    if (mode !== 'edit' || dragIndex == null) return
    dragMoved = true
    editCoords[dragIndex] = [e.lngLat.lng, e.lngLat.lat]
    renderEdit()
    emit()
  }

  const onEditUp = () => {
    if (mode !== 'edit' || dragIndex == null) return
    dragIndex = null
    map.dragPan.enable()
  }

  /**
   * Klik pada garis (bukan titik) = sisipkan titik belok di segmen terdekat.
   * Dua penjaga mencegah sisipan tak sengaja: (1) klik yang mengakhiri sebuah
   * geser, dan (2) klik yang jatuh tepat di atas titik yang sudah ada.
   */
  const onLineClick = (e: MapLayerMouseEvent) => {
    if (mode !== 'edit') return
    if (dragMoved) {
      dragMoved = false
      return
    }
    const onVertex = map.queryRenderedFeatures(e.point, { layers: ['cable-tool-verts-l'] }).length > 0
    if (onVertex) return
    const p: [number, number] = [e.lngLat.lng, e.lngLat.lat]
    let bestSeg = 1
    let bestDist = Infinity
    for (let i = 1; i < editCoords.length; i++) {
      const d = distanceToSegment(p, editCoords[i - 1], editCoords[i])
      if (d < bestDist) {
        bestDist = d
        bestSeg = i
      }
    }
    editCoords.splice(bestSeg, 0, p)
    renderEdit()
    emit()
  }

  // Klik ganda titik belok = hapus (ujung tak bisa dihapus).
  const onVertDblClick = (e: MapLayerMouseEvent) => {
    if (mode !== 'edit') return
    e.preventDefault()
    const f = e.features?.[0]
    if (!f) return
    const idx = Number(f.properties?.index)
    if (Number.isInteger(idx) && idx > 0 && idx < editCoords.length - 1) {
      editCoords.splice(idx, 1)
      renderEdit()
      emit()
    }
  }

  // ---------- pasang/lepas handler ----------

  function attachDraw() {
    // Klik-ganda dinonaktifkan agar tidak men-zoom saat menaruh titik.
    map.doubleClickZoom.disable()
    map.on('mousemove', onMove)
    map.on('click', onClickDraw)
    map.getCanvas().style.cursor = 'crosshair'
  }
  function detachDraw() {
    map.off('mousemove', onMove)
    map.off('click', onClickDraw)
    map.doubleClickZoom.enable()
    map.getCanvas().style.cursor = ''
  }
  function attachEdit() {
    map.doubleClickZoom.disable()
    map.on('mousedown', 'cable-tool-verts-l', onVertMouseDown)
    map.on('dblclick', 'cable-tool-verts-l', onVertDblClick)
    map.on('click', 'cable-tool-hit-l', onLineClick)
    map.on('mousemove', onEditMove)
    map.on('mouseup', onEditUp)
  }
  function detachEdit() {
    map.off('mousedown', 'cable-tool-verts-l', onVertMouseDown)
    map.off('dblclick', 'cable-tool-verts-l', onVertDblClick)
    map.off('click', 'cable-tool-hit-l', onLineClick)
    map.off('mousemove', onEditMove)
    map.off('mouseup', onEditUp)
    map.doubleClickZoom.enable()
    map.dragPan.enable()
  }

  function reset() {
    from = null
    to = null
    bends = []
    cursor = null
    snap = null
    editEndpoints = null
    editCoords = []
    dragIndex = null
    setLine([])
    setVerts([])
    setSnapRing(null)
  }

  // ---------- API publik ----------

  return {
    startDraw() {
      if (mode !== 'idle') this.cancel()
      ensureSourcesAndLayers()
      mode = 'draw'
      reset()
      attachDraw()
      emit()
    },

    startEdit(cable: EditableCable) {
      if (mode !== 'idle') this.cancel()
      ensureSourcesAndLayers()
      mode = 'edit'
      reset()
      editCoords = cable.route.map((p) => [p.longitude, p.latitude])
      const first = editCoords[0]
      const last = editCoords[editCoords.length - 1]
      editEndpoints = {
        from: { kind: cable.fromKind, id: cable.fromId, code: cable.code, lng: first[0], lat: first[1] },
        to: { kind: cable.toKind, id: cable.toId, code: cable.code, lng: last[0], lat: last[1] },
      }
      attachEdit()
      renderEdit()
      emit()
    },

    removeLastBend() {
      if (mode === 'draw' && !to && bends.length > 0) {
        bends.pop()
        renderDraw()
        emit()
      }
    },

    cancel() {
      if (mode === 'draw') detachDraw()
      if (mode === 'edit') detachEdit()
      mode = 'idle'
      reset()
      map.getCanvas().style.cursor = ''
      emit()
    },

    destroy() {
      if (mode === 'draw') detachDraw()
      if (mode === 'edit') detachEdit()
      mode = 'idle'
      removeLayersAndSources()
    },

    route() {
      return currentRoute()
    },
  }
}

/** Jarak titik ke segmen garis, dalam derajat (cukup untuk memilih segmen terdekat). */
function distanceToSegment(p: [number, number], a: [number, number], b: [number, number]): number {
  const dx = b[0] - a[0]
  const dy = b[1] - a[1]
  const lenSq = dx * dx + dy * dy
  const t = lenSq === 0 ? 0 : Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / lenSq))
  const cx = a[0] + t * dx
  const cy = a[1] + t * dy
  return Math.hypot(p[0] - cx, p[1] - cy)
}
