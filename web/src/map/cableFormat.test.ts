import { describe, expect, it } from 'vitest'
import { drawHint, waypointCommands } from './cableFormat'
import { canTapAt, type SnappedDevice, type ToolState } from './cableTool'

const device = (id: string, code: string, kind: SnappedDevice['kind'] = 'ODP'): SnappedDevice => ({
  kind,
  id,
  code,
  lng: 106.8,
  lat: -6.2,
})

const state = (over: Partial<ToolState>): ToolState => ({
  mode: 'draw',
  from: null,
  to: null,
  waypoints: [],
  bendCount: 0,
  lengthMeters: 0,
  cableType: null,
  valid: false,
  complete: false,
  ...over,
})

describe('singgahan kabel hasil gambar', () => {
  // Kotak yang sengaja diklik saat menarik kabel hampir selalu kotak yang dibuka
  // untuk mengambil core — itulah kenapa bawaannya dikupas, bukan cuma lewat.
  it('menganggap kotak yang diklik sebagai kupasan sampai dikatakan sebaliknya', () => {
    const commands = waypointCommands([device('a', 'ODP-01'), device('b', 'ODP-02')], {})
    expect(commands).toEqual([
      { nodeKind: 'ODP', nodeId: 'a', role: 'TAPPED' },
      { nodeKind: 'ODP', nodeId: 'b', role: 'TAPPED' },
    ])
  })

  // Urutan daftar = urutan sepanjang bentang; ia yang jadi urutan singgahan di
  // server, jadi menukarnya berarti menukar letak sambungan di sepanjang kabel.
  it('menuruti peran yang dipilih operator tanpa mengubah urutannya', () => {
    const commands = waypointCommands([device('a', 'ODP-01'), device('b', 'JB-07', 'JOINT_BOX')], {
      a: 'PASSING',
    })
    expect(commands).toEqual([
      { nodeKind: 'ODP', nodeId: 'a', role: 'PASSING' },
      { nodeKind: 'JOINT_BOX', nodeId: 'b', role: 'TAPPED' },
    ])
  })

  // Kabel yang "lewat" rumah pelanggan tak pernah dikupas di sana — ia berhenti
  // di ONU. Idem POP & badan OLT.
  it('cuma mengizinkan kotak yang bisa dibuka orang jadi singgahan', () => {
    expect(['ODC', 'ODP', 'JOINT_BOX', 'ODF'].every((k) => canTapAt(k as SnappedDevice['kind']))).toBe(true)
    expect(['CUSTOMER', 'SITE', 'OLT'].some((k) => canTapAt(k as SnappedDevice['kind']))).toBe(false)
  })
})

describe('petunjuk saat menggambar kabel', () => {
  it('menuntun dari memilih sumber sampai memilih tujuan', () => {
    expect(drawHint(state({}))).toContain('Klik perangkat sumber')
    expect(drawHint(state({ from: device('a', 'ODC-01', 'ODC') }))).toContain('Dari ODC-01')
  })

  // Menerus adalah gerakan yang tak akan ditebak siapa pun kalau tak disebut,
  // padahal ia yang membedakan satu selubung jujur dari rantai kabel pendek.
  it('menyebut cara menerus dan kotak yang sudah disinggahi', () => {
    const hint = drawHint(
      state({
        from: device('a', 'ODC-01', 'ODC'),
        to: device('c', 'ODP-09'),
        waypoints: [device('b', 'ODP-08')],
        complete: true,
      }),
    )
    expect(hint).toContain('Sampai ODP-09')
    expect(hint).toContain('mampir di ODP-08')
    expect(hint).toContain('Klik kotak berikutnya')
  })
})
