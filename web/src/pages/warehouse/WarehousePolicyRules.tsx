import { useCallback, useState } from 'react'
import { POLICY_OPERATIONS } from '@/api/warehouse/approvalReads'
import { policyApprovers, type PolicyChoice } from '@/api/warehouse/policy'
import { Button, SelectField, TextField } from '@/components/atoms'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { approvalOperationLabels } from './approvalPresentation'
import type { PolicyLocation, PolicyRuleDraft } from './policyDraft'

export function WarehousePolicyRules({ rules, locations, onChange }: { rules: PolicyRuleDraft[]; locations: PolicyLocation[]; onChange: (rules: PolicyRuleDraft[]) => void }) {
  function change(index: number, rule: PolicyRuleDraft) { onChange(rules.map((current, i) => i === index ? rule : current)) }
  return <div className="stack">{rules.map((rule, index) => <fieldset key={index} className="stack" style={{ minWidth: 0 }}><legend>Aturan {index + 1}</legend>
    <SelectField label={`Jenis transaksi aturan ${index + 1}`} value={rule.operation} onChange={(_, data) => change(index, { ...rule, operation: data.value as PolicyRuleDraft['operation'] })}>
      {POLICY_OPERATIONS.map(operation => <option key={operation} value={operation}>{approvalOperationLabels[operation]}</option>)}
    </SelectField>
    {rule.tiers.map((tier, tierIndex) => <PolicyTier key={tierIndex} tier={tier} locations={locations} ruleNumber={index + 1} tierNumber={tierIndex + 1}
      onChange={value => change(index, { ...rule, tiers: rule.tiers.map((current, i) => i === tierIndex ? value : current) })}
      onRemove={() => change(index, { ...rule, tiers: rule.tiers.filter((_, i) => i !== tierIndex) })} />)}
    <div className="row wrap"><Button type="button" disabled={rule.tiers.length >= 10} onClick={() => change(index, { ...rule, tiers: [...rule.tiers, { minimum: '', members: [] }] })}>Tambah tahap aturan {index + 1}</Button>
      <Button type="button" onClick={() => onChange(rules.filter((_, i) => i !== index))}>Hapus aturan {index + 1}</Button></div>
  </fieldset>)}<Button type="button" disabled={rules.length >= 9} onClick={() => onChange([...rules, { operation: POLICY_OPERATIONS.find(operation => !rules.some(rule => rule.operation === operation)) ?? 'ADJUSTMENT', tiers: [{ minimum: '1', members: [] }] }])}>Tambah aturan transaksi</Button></div>
}
function PolicyTier({ tier, locations, ruleNumber, tierNumber, onChange, onRemove }: { tier: PolicyRuleDraft['tiers'][number]; locations: PolicyLocation[]; ruleNumber: number; tierNumber: number; onChange: (tier: PolicyRuleDraft['tiers'][number]) => void; onRemove: () => void }) {
  const [kind, setKind] = useState<'USER' | 'ROLE'>('USER'), [selected, setSelected] = useState<PolicyChoice | null>(null)
  const loader = useCallback((query: string, page: number) => policyApprovers(locations.map(row => row.id), kind, query, page), [locations, kind])
  const suffix = `aturan ${ruleNumber} tahap ${tierNumber}`
  return <fieldset className="stack" style={{ minWidth: 0 }}><legend>Tahap {tierNumber}</legend>
    <TextField label={`Batas nilai minimum ${suffix}`} required inputMode="numeric" maxLength={38} value={tier.minimum} onChange={(_, data) => onChange({ ...tier, minimum: data.value })} hint="Bilangan bulat dalam unit terkecil mata uang kebijakan; batas tahap berikutnya harus lebih besar." />
    <ul>{tier.members.map(member => <li key={`${member.kind}:${member.id}`} className="row wrap"><span>{member.kind === 'ROLE' ? 'Role' : 'Pengguna'}: {member.name}</span>
      <Button type="button" aria-label={`Hapus ${member.name} dari ${suffix}`} onClick={() => onChange({ ...tier, members: tier.members.filter(row => row.kind !== member.kind || row.id !== member.id) })}>Hapus pemeriksa</Button></li>)}</ul>
    <SelectField label={`Jenis pemeriksa ${suffix}`} value={kind} onChange={(_, data) => { setKind(data.value as 'USER' | 'ROLE'); setSelected(null) }}><option value="USER">Pengguna</option><option value="ROLE">Role</option></SelectField>
    <WarehousePicker label={`Pemeriksa ${suffix}`} load={loader} value={selected} optional name={row => row.name} disabled={!locations.length} onChange={setSelected} eligible={row => !tier.members.some(member => member.kind === kind && member.id === row.id)} />
    <div className="row wrap"><Button type="button" disabled={!selected || tier.members.length >= 100 || tier.members.some(member => member.kind === kind && member.id === selected.id)} onClick={() => { if (selected) { onChange({ ...tier, members: [...tier.members, { ...selected, kind }] }); setSelected(null) } }}>Tambah pemeriksa {suffix}</Button>
      <Button type="button" onClick={onRemove}>Hapus tahap {tierNumber} aturan {ruleNumber}</Button></div>
  </fieldset>
}
