import { expect, it } from 'vitest'
import { readyRmaReturn, repairTransit, returnBin, returnDetailsFixture, returnFixture, returnIds as id, returnQuarantine, returnSourceFixture } from '@/test/warehouseReturnFixture'
import { buildRepairDispatch, buildRepairReceipt, buildReturnInspection, buildReturnIntake, needsPostRepairInspection } from './returnDraft'

it('matches mixed-case receipt identity without rewriting the observed command or historical serial', () => {
  const details = readyRmaReturn()
  details.references.item.serial = 'Onu-001'
  const before = JSON.stringify(details), observed = ' onu-001 '
  expect(buildReturnInspection(details, returnQuarantine, '1', 'SERVICEABLE', 'cek', observed, true, 'reset').observedSerial).toBe(observed)
  const vendor = { id: id.vendor, revision: 0, state: 'ACTIVE' as const, code: 'SERVICE', name: 'Servis', contactReference: null }
  const dispatch = { ...details, returnCase: { ...details.returnCase, repair: null } }
  expect(buildRepairDispatch(dispatch, vendor, repairTransit, observed, 'SERV-001', 'bukti').observedSerial).toBe(observed)
  const receipt = { ...details, returnCase: { ...details.returnCase, state: 'REPAIR' as const, repair: { ...details.returnCase.repair!, returnedRevision: null } } }
  expect(buildRepairReceipt(receipt, returnQuarantine, observed, 'REPAIRED', 'KEMBALI', 'bukti').observedSerial).toBe(observed)
  for (const wrong of ['', ' ', 'ONU-002']) {
    expect(() => buildReturnInspection(details, returnQuarantine, '1', 'SERVICEABLE', 'cek', wrong, true, 'reset')).toThrow('serial fisik yang sama')
    expect(() => buildRepairDispatch(dispatch, vendor, repairTransit, wrong, 'SERV-001', 'bukti')).toThrow('serial fisik yang sama')
    expect(() => buildRepairReceipt(receipt, returnQuarantine, wrong, 'REPAIRED', 'KEMBALI', 'bukti')).toThrow('serial fisik yang sama')
  }
  expect(JSON.stringify(details)).toBe(before)
})

it('binds intake to the selected actual source and its acknowledged quarantine', () => {
  expect(buildReturnIntake(returnSourceFixture(), returnQuarantine, ' bukti ')).toEqual({ origin: 'MATERIAL_RESIDUAL', sourceDocumentId: id.returnSource, quarantineLocationId: id.inspection, evidenceReference: 'bukti' })
  expect(() => buildReturnIntake(returnSourceFixture(), { ...returnQuarantine, id: id.source }, 'bukti')).toThrow('penerimaan aslinya')
  expect(() => buildReturnIntake(returnSourceFixture(true), returnBin, 'bukti')).toThrow('karantina')
})
it('measures the whole cable remnant exactly without adding, rounding or merging quantity', () => {
  const details = returnDetailsFixture()
  const build = (amount: string) => buildReturnInspection(details, returnBin, amount, 'SERVICEABLE', 'ukur', '', false, '')
  expect(build('17,500')).toEqual({ expectedRevision: 0, measuredQuantityBase: '17500', condition: 'SERVICEABLE', destinationLocationId: id.source, evidenceReference: 'ukur', resetConfirmed: false })
  expect(() => build('17,501')).toThrow('seluruh potongan')
  expect(() => build('17,5')).not.toThrow()
  expect(() => buildReturnInspection(details, returnBin, '17,500', 'SERVICEABLE', 'ukur', 'FAKE', false, '')).toThrow('tanpa serial')
  expect(() => buildReturnInspection(details, returnBin, '17,500', 'SCRAP', 'ukur', '', false, '')).toThrow('keputusan independen')
})
it('customer property stays in quarantine after a verified serial and reset checklist', () => {
  const details = returnDetailsFixture(returnFixture(true))
  const build = (reset = true, serial = 'ONU-001') => buildReturnInspection(details, returnQuarantine, '1', 'SERVICEABLE', 'cek', serial, reset, 'reset-bersih')
  expect(build()).toMatchObject({ expectedRevision: 1, observedSerial: 'ONU-001', resetConfirmed: true, resetEvidenceReference: 'reset-bersih', destinationLocationId: id.inspection })
  expect(() => build(false)).toThrow('Konfirmasikan reset')
  expect(() => build(true, 'ONU-OTHER')).toThrow('serial fisik yang sama')
  expect(() => buildReturnInspection(details, returnBin, '1', 'SERVICEABLE', 'cek', 'ONU-001', true, 'reset')).toThrow('karantina')
  expect(() => buildReturnInspection({ ...details, references: { ...details.references, rmaHandoverId: id.line } }, returnQuarantine, '1', 'SERVICEABLE', 'cek', 'ONU-001', true, 'reset')).toThrow('tidak sedang menunggu')
})
it('repair uses the same physical serial and requires a fresh inspection after vendor receipt', () => {
  const view = returnFixture(true)
  const inspected = { ...view, revision: 2, condition: 'DAMAGED' as const, inspection: { expectedRevision: 1, measuredQuantityBase: '1', condition: 'DAMAGED' as const,
    destinationLocationId: id.inspection, evidenceReference: 'cek', resetConfirmed: false, observedSerial: 'ONU-001', resetEvidenceReference: null } }
  const details = returnDetailsFixture(inspected)
  const vendor = { id: id.vendor, revision: 0, state: 'ACTIVE' as const, code: 'SERVICE', name: 'Penyedia servis', contactReference: null }
  expect(buildRepairDispatch(details, vendor, repairTransit, 'ONU-001', 'SERV-001', 'bukti')).toMatchObject({ expectedRevision: 2, vendorId: id.vendor, observedSerial: 'ONU-001' })
  expect(() => buildRepairDispatch(returnDetailsFixture(view), vendor, repairTransit, 'ONU-001', 'SERV-001', 'bukti')).toThrow('sudah diperiksa')
  const outbound = { ...inspected, revision: 3, state: 'REPAIR' as const, locationId: id.transit,
    repair: { id: id.repair, vendorId: id.vendor, vendorReference: 'SERV-001', repairLocationId: id.transit, dispatchRevision: 3, returnedRevision: null, result: null, receiptReference: null } }
  expect(buildRepairReceipt(returnDetailsFixture(outbound), returnQuarantine, 'ONU-001', 'REPAIRED', 'KEMBALI', 'bukti')).toMatchObject({ expectedRevision: 3, quarantineLocationId: id.inspection })
  expect(() => buildRepairReceipt(returnDetailsFixture(outbound), returnQuarantine, 'BARU-002', 'REPAIRED', 'KEMBALI', 'bukti')).toThrow('serial fisik yang sama')
  const inbound = { ...outbound, revision: 4, state: 'RECEIVED_IN_INSPECTION' as const, locationId: id.inspection, repair: { ...outbound.repair, returnedRevision: 4, result: 'REPAIRED' as const, receiptReference: 'KEMBALI' } }
  expect(needsPostRepairInspection(returnDetailsFixture(inbound))).toBe(true)
  expect(needsPostRepairInspection(returnDetailsFixture({ ...inbound, revision: 5, inspection: { ...inbound.inspection, expectedRevision: 4 } }))).toBe(false)
})
