import { expect, it } from 'vitest'
import { myContext, myIssue } from '@/test/myMaterialsFixture'
import { custodyFixture } from '@/test/warehouseExecutionFixture'
import { materialIds as id } from '@/test/warehouseMaterialFixture'
import { myReceiptInput, myReturnInput } from './myMaterialDraft'

it('uses actual issue revision and dispatch identity and rejects a foreign receiver or changed work order', () => {
  const build = (context = myContext(), issue = myIssue(), actor = id.inspection) => myReceiptInput(context, issue, actor, id.line, '60', '40', '0', 'Short delivery', 'Signed handover', null)
  expect(build()).toMatchObject({ expectedRevision: 2, workOrderRevision: 5, lines: [{ stockIdentityId: id.piece, acceptedBase: '60000', missingBase: '40000' }] })
  expect(() => build(myContext(), myIssue(), id.supplier)).toThrow()
  expect(() => build({ ...myContext(), workOrderRevision: 6 })).toThrow()
  expect(() => build({ ...myContext(), currentAssignee: false })).toThrow()
})
it.each(['ONU-01', 'Onu-01'])('requires observed serial and preserves unit limits for receipt %s', (recorded) => {
  const issue = myIssue(); issue.lines[0] = { ...issue.lines[0], sku: { ...issue.lines[0].sku, baseUnit: 'EA', tracking: 'SERIAL' }, baseUnit: 'EA', dispatchedBase: '1', remainingBase: '1', serial: recorded }
  const build = (serial: string, amount = '1') => myReceiptInput(myContext(), issue, id.inspection, id.line, amount, '0', '0', '', 'Signed serial', serial)
  expect(() => build('FOREIGN')).toThrow(); expect(() => build('ONU-01', '1.5')).toThrow(); expect(() => build('ONU-01', '2')).toThrow()
  expect(build('onu-01').lines[0].serial).toBe(recorded)
})
it('allows exact own remainder return after reassignment without fabricating a usage source', () => {
  const source = { ...custodyFixture(), quantityBase: '17500', sourceUsageId: id.evidence, initialUseSource: false }
  const context = { ...myContext(), currentAssignee: false, field: null, workOrderRevision: 9 }
  expect(myReturnInput(context, source, '17,500', id.allocation, 'Unused', 'Signed return')).toMatchObject({ workOrderRevision: 9, quantityBase: '17500', usageId: id.evidence, stockIdentityId: source.id })
  expect(() => myReturnInput(context, source, '17,501', id.allocation, 'Unused', 'Signed')).toThrow()
  expect(() => myReturnInput(context, source, '17,500', null, 'Unused', 'Signed')).toThrow()
})
it.each(['ONU-01', 'Onu-01'])('requires observed serial for unused return %s without splitting or substituting a device', (recorded) => {
  const source = { ...custodyFixture(), quantityBase: '1', baseUnit: 'EA' as const, serial: recorded, sku: { ...custodyFixture().sku, tracking: 'SERIAL' as const, baseUnit: 'EA' as const } }
  const build = (serial: string | null, quantity = '1') => myReturnInput(myContext(), source, quantity, id.allocation, 'Unused device', 'Signed return', serial)
  expect(() => build(null)).toThrow(); expect(() => build('ONU-02')).toThrow(); expect(() => build('ONU-01', '0.5')).toThrow()
  expect(build('onu-01')).toMatchObject({ stockIdentityId: id.piece, quantityBase: '1', baseUnit: 'EA', usageId: undefined })
})
