import { beforeEach, describe, expect, it } from 'vitest'
import type { Map as MapLibreMap } from 'maplibre-gl'
import { createCableTool, type ToolState } from './cableTool'

/**
 * Alat gambar kabel diuji lewat PETA BOHONGAN: MapLibre asli butuh WebGL yang tak
 * ada di jsdom, padahal yang mau dijaga di sini bukan gambarnya melainkan aturan
 * geraknya — kapan kabel dianggap sampai, dan kapan ia BOLEH terbuka lagi.
 *
 * Yang dijaga paling keras: sesudah kabel didrop di perangkat tujuan, ia putus di
 * situ. Dulu garisnya terus mengekor kursor dan klik meleset sekali pun diam-diam
 * menurunkan tujuannya jadi singgahan — operator menyangka kabelnya tak pernah
 * selesai, lalu menggambar ulang berkali-kali.
 */

type FakeDevice = { layer: string; id: string; code: string; x: number; y: number; lng: number; lat: number }

function fakeMap(devices: FakeDevice[]) {
  const sources = new Map<string, { data: unknown }>()
  const layers = new Set<string>(devices.map((d) => d.layer))
  const handlers = new Map<string, Array<(e: unknown) => void>>()
  const canvas = { style: { cursor: '' } }
  const noop = { enable() {}, disable() {} }

  const map = {
    getSource: (id: string) => {
      const src = sources.get(id)
      return src ? { setData: (data: unknown) => { src.data = data } } : undefined
    },
    addSource: (id: string) => { sources.set(id, { data: null }) },
    removeSource: (id: string) => { sources.delete(id) },
    getLayer: (id: string) => (layers.has(id) ? { id } : undefined),
    addLayer: (l: { id: string }) => { layers.add(l.id) },
    removeLayer: (id: string) => { layers.delete(id) },
    queryRenderedFeatures: (box: [[number, number], [number, number]], opts: { layers: string[] }) => {
      const [[x1, y1], [x2, y2]] = box
      return devices
        .filter((d) => opts.layers.includes(d.layer) && d.x >= x1 && d.x <= x2 && d.y >= y1 && d.y <= y2)
        .map((d) => ({
          layer: { id: d.layer },
          properties: { id: d.id, code: d.code },
          geometry: { type: 'Point', coordinates: [d.lng, d.lat] },
        }))
    },
    getCanvas: () => canvas,
    on: (ev: string, fn: (e: unknown) => void) => {
      handlers.set(ev, [...(handlers.get(ev) ?? []), fn])
    },
    off: (ev: string, fn: (e: unknown) => void) => {
      handlers.set(ev, (handlers.get(ev) ?? []).filter((h) => h !== fn))
    },
    doubleClickZoom: noop,
    dragPan: noop,
  }

  const fire = (ev: string, x: number, y: number) => {
    // Koordinat peta dibuat sebangun dengan piksel (1 px = 0.001°) supaya letak
    // klik di uji ini bisa dibaca langsung tanpa menghitung proyeksi.
    const e = { point: { x, y }, lngLat: { lng: 106 + x / 1000, lat: -6 - y / 1000 } }
    for (const h of handlers.get(ev) ?? []) h(e)
  }

  return {
    map: map as unknown as MapLibreMap,
    /** Gerakkan kursor lalu klik — persis urutan kejadian di peramban. */
    click: (x: number, y: number) => { fire('mousemove', x, y); fire('click', x, y) },
    hover: (x: number, y: number) => fire('mousemove', x, y),
    /** Koordinat garis karet yang sedang tergambar. */
    line: (): Array<[number, number]> => {
      const data = sources.get('cable-tool-line')?.data as
        | { geometry?: { coordinates?: Array<[number, number]> } }
        | null
      return data?.geometry?.coordinates ?? []
    },
    cursor: () => canvas.style.cursor,
  }
}

const odc: FakeDevice = { layer: 'odc', id: 'c1', code: 'ODC-01', x: 10, y: 10, lng: 106.01, lat: -6.01 }
const odp1: FakeDevice = { layer: 'odp', id: 'p1', code: 'ODP-01', x: 50, y: 50, lng: 106.05, lat: -6.05 }
const odp2: FakeDevice = { layer: 'odp', id: 'p2', code: 'ODP-02', x: 90, y: 50, lng: 106.09, lat: -6.05 }
const rumah: FakeDevice = { layer: 'customer', id: 'u1', code: 'CUST-01', x: 90, y: 90, lng: 106.09, lat: -6.09 }

describe('menggambar kabel: kabel putus di perangkat tujuan', () => {
  let env: ReturnType<typeof fakeMap>
  let state: ToolState
  let tool: ReturnType<typeof createCableTool>

  beforeEach(() => {
    env = fakeMap([odc, odp1, odp2, rumah])
    state = {} as ToolState
    tool = createCableTool(env.map, (s) => { state = s })
    tool.startDraw()
  })

  it('berhenti mengekor kursor begitu kabel didrop di kotak tujuan', () => {
    env.click(odc.x, odc.y)
    env.click(odp1.x, odp1.y)
    expect(state.complete).toBe(true)

    // Kursor menyapu peta kosong: garisnya tetap dua titik — ODC ke ODP, habis.
    env.hover(200, 200)
    expect(env.line()).toEqual([[odc.lng, odc.lat], [odp1.lng, odp1.lat]])
    expect(env.cursor()).toBe('')
  })

  it('mengabaikan klik di peta kosong sesudah kabel sampai tujuan', () => {
    env.click(odc.x, odc.y)
    env.click(odp1.x, odp1.y)
    env.click(200, 200)

    expect(state.complete).toBe(true)
    expect(state.to?.code).toBe('ODP-01')
    expect(state.waypoints).toEqual([])
    expect(state.bendCount).toBe(0)
  })

  // Refleks "klik dua kali untuk mengakhiri" datang dari alat gambar lain; di sini
  // klik keduanya jatuh di kotak yang sama dan dulu justru membuka kabelnya lagi.
  it('mengabaikan klik ulang di kotak tujuan itu sendiri', () => {
    env.click(odc.x, odc.y)
    env.click(odp1.x, odp1.y)
    env.click(odp1.x, odp1.y)

    expect(state.complete).toBe(true)
    expect(state.waypoints).toEqual([])
    expect(env.line()).toEqual([[odc.lng, odc.lat], [odp1.lng, odp1.lat]])
  })

  it('memakai kursor sebagai pratayang hanya selagi jalurnya masih boleh memanjang', () => {
    env.click(odc.x, odc.y)
    env.hover(30, 30)
    expect(env.line()).toHaveLength(2) // pangkal + kursor
    expect(env.cursor()).toBe('crosshair')

    env.click(odp1.x, odp1.y)
    // Menyorot kotak sah berikutnya = pratayang "kalau diklik, nyambung ke sini".
    env.hover(odp2.x, odp2.y)
    expect(env.line()).toEqual([[odc.lng, odc.lat], [odp1.lng, odp1.lat], [odp2.lng, odp2.lat]])
    expect(env.cursor()).toBe('pointer')
  })
})

describe('menggambar kabel: meneruskan selubung', () => {
  it('menerus saat kotak berikutnya diklik — satu selubung, bukan dua kabel', () => {
    const env = fakeMap([odc, odp1, odp2])
    let state = {} as ToolState
    const tool = createCableTool(env.map, (s) => { state = s })
    tool.startDraw()

    env.click(odc.x, odc.y)
    env.click(odp1.x, odp1.y)
    env.click(odp2.x, odp2.y)

    expect(state.to?.code).toBe('ODP-02')
    expect(state.waypoints.map((w) => w.code)).toEqual(['ODP-01'])
    expect(state.complete).toBe(true)
  })

  // Kabelnya menerus TAPI memutar dulu ikut gang — jalur yang mustahil digambar
  // kalau sesudah kotak tujuan cuma boleh lurus ke kotak berikutnya.
  it('membuka lagi jalurnya lewat gestur yang disengaja, lalu boleh berbelok', () => {
    const env = fakeMap([odc, odp1, odp2])
    let state = {} as ToolState
    const tool = createCableTool(env.map, (s) => { state = s })
    tool.startDraw()

    env.click(odc.x, odc.y)
    env.click(odp1.x, odp1.y)
    expect(state.canContinue).toBe(true)

    tool.continueSheath()
    expect(state.complete).toBe(false)
    expect(state.waypoints.map((w) => w.code)).toEqual(['ODP-01'])

    env.click(70, 20)
    expect(state.bendCount).toBe(1)
    env.click(odp2.x, odp2.y)
    expect(state.to?.code).toBe('ODP-02')
    expect(state.waypoints.map((w) => w.code)).toEqual(['ODP-01'])
    expect(state.complete).toBe(true)
  })

  it('mengurungkan penerusan yang telanjur ditekan, satu klik pada satu waktu', () => {
    const env = fakeMap([odc, odp1, odp2])
    let state = {} as ToolState
    const tool = createCableTool(env.map, (s) => { state = s })
    tool.startDraw()

    env.click(odc.x, odc.y)
    env.click(odp1.x, odp1.y)
    tool.continueSheath()
    env.click(70, 20)

    tool.removeLastBend()
    expect(state.bendCount).toBe(0)
    expect(state.waypoints.map((w) => w.code)).toEqual(['ODP-01'])

    // Sekali lagi: kotak yang tadi turun jadi singgahan naik lagi jadi ujung —
    // kabelnya kembali "sampai ODP-01", siap disimpan seperti sebelum ditekan.
    tool.removeLastBend()
    expect(state.to?.code).toBe('ODP-01')
    expect(state.waypoints).toEqual([])
    expect(state.complete).toBe(true)
  })

  // Kabel drop berhenti di ONU rumah pelanggan: tak ada yang mengupas selubung di
  // situ, jadi tombol "Teruskan selubung" pun tak boleh ditawarkan.
  it('tak menawarkan menerus di ujung yang tak bisa dibuka orang', () => {
    const env = fakeMap([odp1, rumah])
    let state = {} as ToolState
    const tool = createCableTool(env.map, (s) => { state = s })
    tool.startDraw()

    env.click(odp1.x, odp1.y)
    env.click(rumah.x, rumah.y)

    expect(state.complete).toBe(true)
    expect(state.canContinue).toBe(false)
    tool.continueSheath()
    expect(state.complete).toBe(true)
    expect(state.waypoints).toEqual([])
  })
})
