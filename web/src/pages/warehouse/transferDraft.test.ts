import { expect, it } from 'vitest'
import { transferFixture, transferIds as id, transferLocations, transferPositionFixture } from '@/test/warehouseTransferFixture'
import { buildTransferDraft, buildTransferReceipt } from './transferDraft'

it('captures the actual source balance and exact metres while refusing allocated or other-custodian stock', () => {
  const position = transferPositionFixture(), rows = [{ key: 'a', position, quantity: '60,125' }]
  const [source, destination, transit] = transferLocations
  const build = (input = rows) => buildTransferDraft(source, destination, transit, id.receiver, id.sender, 'Pindah gudang', input)
  expect(build().lines).toEqual([{ stockIdentityId: id.piece, sourceBalanceId: id.position, quantityBase: '60125', baseUnit: 'MM' }])
  expect(() => build([{ ...rows[0], quantity: '1000,001' }])).toThrow('melebihi fisik')
  expect(() => build([{ ...rows[0], position: { ...position, reservedUnpicked: { ...position.reservedUnpicked, quantityBase: '1' } } }])).toThrow('dicadangkan')
  expect(() => build([{ ...rows[0], position: { ...position, custodianKind: 'TECHNICIAN', custodianId: id.receiver } }])).toThrow('alur material WO')
  expect(() => build([rows[0], { ...rows[0], key: 'b' }])).toThrow('identitas barang berbeda')
})
it('rejects shared transit boundaries and a receiver who does not own the vehicle destination', () => {
  const [source, destination, transit] = transferLocations, rows = [{ key: 'a', position: transferPositionFixture(), quantity: '100' }]
  expect(() => buildTransferDraft(source, destination, { ...transit, code: 'RECEIPT_SOURCE' }, id.receiver, id.sender, 'Pindah', rows)).toThrow('berbeda dan sesuai')
  expect(() => buildTransferDraft(source, { ...destination, kind: 'VEHICLE', custodianId: id.sender }, transit, id.receiver, id.sender, 'Pindah', rows)).toThrow('penanggung jawab')
})
it('accepts only the measured receipt up to current transit, preserving the remaining obligation', () => {
  const draft = transferFixture(), partial = { ...draft, state: 'PART_RECEIVED' as const, revision: 2, lines: [{ ...draft.lines[0], receivedBase: '60000', inTransitBase: '40000' }] }
  expect(buildTransferReceipt(partial, ' SJ-002 ', [{ lineId: id.line, selected: true, quantity: '17,500' }])).toEqual({ expectedRevision: 2, evidenceReference: 'SJ-002', lines: [{ lineId: id.line, quantityBase: '17500', baseUnit: 'MM' }] })
  expect(() => buildTransferReceipt(partial, 'SJ', [{ lineId: id.line, selected: true, quantity: '40,001' }])).toThrow('melebihi sisa')
  expect(() => buildTransferReceipt(draft, 'SJ', [{ lineId: id.line, selected: true, quantity: '1' }])).toThrow('tidak sedang menunggu')
})
