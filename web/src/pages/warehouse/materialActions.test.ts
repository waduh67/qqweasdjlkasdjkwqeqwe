import { expect, it } from 'vitest'
import { reservationAllocation } from '@/api/warehouse/reservations'
import { materialIds as id, allocationFixture, materialSummaryFixture } from '@/test/warehouseMaterialFixture'
import { buildAllocationCommand, buildReservation, currentAllocations } from './materialActions'

const allocation = reservationAllocation(allocationFixture)
it('does not treat historical allocations or repeated demand supply as extra available quantity', () => {
  const rows = [allocation, { ...allocation, id: id.issue, state: 'RELEASED' }, { ...allocation, id: id.piece, documentId: id.evidence }]
  expect(currentAllocations(materialSummaryFixture, rows)).toEqual([allocation])
  expect(() => buildAllocationCommand(materialSummaryFixture, [{ allocation, selected: true, quantity: '60,001', scan: '' }], 'pick')).toThrow('melebihi reservasi')
})
it('rejects a changed stock context, an issue-bound reservation and a mismatched serial scan', () => {
  const draft = { allocation, selected: true, quantity: '1', scan: '' }
  expect(() => buildAllocationCommand(materialSummaryFixture, [{ ...draft, allocation: { ...allocation, documentRevision: 9 } }], 'pick')).toThrow('Alokasi berubah')
  expect(() => buildAllocationCommand(materialSummaryFixture, [{ ...draft, allocation: { ...allocation, reservedPickedBase: '1' } }], 'release', 'Ubah rencana')).toThrow('terikat slip')
  const serial = { ...allocation, baseUnit: 'EA' as const, serial: 'ONU-001', reservedUnpickedBase: '1' }
  expect(() => buildAllocationCommand(materialSummaryFixture, [{ ...draft, allocation: serial, scan: 'ONU-002' }], 'pick')).toThrow('Serial hasil pindai')
  expect(buildAllocationCommand(materialSummaryFixture, [{ ...draft, allocation: serial, scan: ' onu-001 ' }], 'pick')).toMatchObject({ lines: [{ scan: 'onu-001', quantityBase: '1', stockRevision: 4 }] })
})
it('requires real submitted line mapping and refuses reservation above the remaining demand', () => {
  const draft = { planLineId: id.line, selected: true, quantity: '40,001', position: null }
  expect(() => buildReservation(materialSummaryFixture, [draft], '', false)).toThrow('melebihi sisa')
  expect(() => buildReservation({ ...materialSummaryFixture, lines: materialSummaryFixture.lines.map(line => ({ ...line, demandLineId: null })) }, [{ ...draft, quantity: '40' }], '', false)).toThrow('belum terverifikasi')
})
it('releases exact amounts with an explanation without sending picking-only identity fields', () => {
  expect(buildAllocationCommand(materialSummaryFixture, [{ allocation, selected: true, quantity: '12,345', scan: '' }], 'release', ' Revisi kebutuhan ')).toEqual({ expectedRevision: 2, workOrderRevision: 5, planRevision: 1, reason: 'Revisi kebutuhan', allocations: [{ reservationId: id.supplier, expectedRevision: 3, quantityBase: '12345' }] })
})
