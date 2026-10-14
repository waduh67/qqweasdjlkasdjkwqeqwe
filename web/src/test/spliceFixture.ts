import type { CableCoreView, SpliceCableView, SplicePointView, SpliceWorkbenchView } from '@/api/network'

const colors = ['Biru', 'Oranye', 'Hijau', 'Cokelat', 'Abu-abu', 'Putih', 'Merah', 'Hitam', 'Kuning', 'Ungu', 'Merah muda', 'Aqua']
export function spliceCable(id = 'in', count = 12): SpliceCableView {
  return { cableId: id, code: id === 'in' ? 'DIST-JB-ODP' : 'DIST-ODP-LANJUTAN', name: id === 'in' ? 'JB ke ODP' : 'Kabel lanjutan', cableType: 'DISTRIBUTION',
    coreCount: count, lengthMeters: 154, role: 'END', roleLabel: 'Berakhir di sini', spliceable: true, terminatesHere: true, tapDistanceMeters: 154,
    cores: Array.from({ length: count }, (_, index) => ({ connectionId: null, connectedElsewhere: index === 0, core: {
      id: `${id}-${index + 1}`, coreNumber: index + 1, tubeNumber: Math.floor(index / 12) + 1, positionInTube: index % 12 + 1,
      color: colors[index % 12], colorHex: '#2563eb', tubeColor: 'Biru', tubeColorHex: '#2563eb', status: index === 0 ? 'USED' : 'FREE', note: null,
    } as CableCoreView })),
  }
}
export function splicePoints(id = 'spl-1'): SplicePointView[] {
  return [{ kind: 'SPLITTER_IN', nodeId: id, portNumber: null, portSide: null, label: `Input ${id}`, group: `${id.toUpperCase()} · 1:16`, connectionId: null, serves: null },
    ...Array.from({ length: 16 }, (_, index): SplicePointView => ({ kind: 'SPLITTER_OUT', nodeId: id, portNumber: index + 1, portSide: null,
      label: `${id} kaki ${index + 1}`, group: `${id.toUpperCase()} · 1:16`, connectionId: null, serves: null }))]
}
export function spliceFixture(): SpliceWorkbenchView {
  return { closureKind: 'ODP', closureId: 'odp-qa', closureCode: 'ODP-CRB-001', closureName: 'ODP Cirebon 001', spliceCapacity: null,
    spliceCount: 0, cables: [spliceCable()], points: splicePoints(), connections: [] }
}
