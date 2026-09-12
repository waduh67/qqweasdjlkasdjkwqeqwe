import { useCallback, useEffect, useMemo, useState } from 'react'
import { Text } from '@fluentui/react-components'
import { ApiError } from '@/api/client'
import {
  decideInventoryApproval,
  listApprovalPolicies,
  listEmergencyOverrides,
  listPendingApprovals,
  operationEnvelope,
  saveApprovalPolicy,
  type ApprovalPolicyView,
  type ApprovalTierView,
  type EmergencyOverrideView,
  type InventoryApprovalRequest,
} from '@/api/inventory'
import { useCan } from '@/auth/useCan'
import { Badge, Button, EmptyState, SelectField, SkeletonRows, TextField, TextareaField } from '@/components/atoms'
import { useToast } from '@/system'
import type { WarehouseReference } from './WarehouseOperationsPage'
import {
  APPROVAL_STATUS_LABEL,
  APPROVAL_STATUS_TONE,
  APPROVAL_TYPE_LABEL,
} from './WarehouseLabels'

type Section = 'queue' | 'policies' | 'overrides'

export function WarehouseApprovalPanel({ reference }: { reference: WarehouseReference }) {
  const [section, setSection] = useState<Section>('queue')

  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <div className="row wrap" role="group" aria-label="Bagian persetujuan">
        <Button variant={section === 'queue' ? 'primary' : 'default'} onClick={() => setSection('queue')}>Antrean</Button>
        <Button variant={section === 'policies' ? 'primary' : 'default'} onClick={() => setSection('policies')}>Kebijakan</Button>
        <Button variant={section === 'overrides' ? 'primary' : 'default'} onClick={() => setSection('overrides')}>Override darurat</Button>
      </div>
      {/* Hanya bagian kebijakan yang menerima acuan: ia menyusun daftar pilihan penyetuju dari
          direktori pengguna. Antrean dan laporan override membaca nama dari read model-nya
          sendiri, jadi keduanya SENGAJA tidak diberi akses ke direktori — supaya tidak ada yang
          diam-diam menggabungkan id di sana lagi dan mengembalikan UUID untuk petugas tanpa
          `iam.user.view`. */}
      {section === 'queue' && <ApprovalQueue />}
      {section === 'policies' && <ApprovalPolicySection reference={reference} />}
      {section === 'overrides' && <EmergencyOverrideSection />}
    </div>
  )
}

// ————————————————————————————— antrean —————————————————————————————

function ApprovalQueue() {
  const { can } = useCan()
  const toast = useToast()
  const canDecide = can('inventory.approval.decide')
  const [approvals, setApprovals] = useState<readonly InventoryApprovalRequest[]>([])
  const [loading, setLoading] = useState(true)
  const [notes, setNotes] = useState<Record<string, string>>({})
  const [busyId, setBusyId] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setApprovals(await listPendingApprovals())
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.message : 'Gagal memuat antrean persetujuan')
      setApprovals([])
    } finally {
      setLoading(false)
    }
  }, [toast])

  useEffect(() => {
    void load()
  }, [load])

  const decide = async (approval: InventoryApprovalRequest, decision: 'APPROVE' | 'REJECT') => {
    const reason = notes[approval.approvalId]?.trim() || null
    setBusyId(approval.approvalId)
    try {
      const envelope = await operationEnvelope({ approvalId: approval.approvalId, decision, reason })
      await decideInventoryApproval(approval.approvalId, decision, reason, envelope)
      toast.success(decision === 'APPROVE' ? 'Permintaan disetujui' : 'Permintaan ditolak')
      setNotes((current) => ({ ...current, [approval.approvalId]: '' }))
      await load()
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.message : 'Keputusan tidak dapat disimpan')
    } finally {
      setBusyId(null)
    }
  }

  if (loading) return <div className="card"><SkeletonRows rows={3} cols={3} /></div>
  if (approvals.length === 0) {
    return (
      <div className="card">
        <EmptyState title="Tidak ada persetujuan" hint="Antrean hanya memuat permintaan yang boleh kamu tinjau." />
      </div>
    )
  }

  return (
    <div className="stack">
      {approvals.map((approval) => {
        const note = notes[approval.approvalId] ?? ''
        const busy = busyId === approval.approvalId
        return (
          <article className="card stack" key={approval.approvalId}>
            <div className="spread wrap">
              <Text as="strong">{APPROVAL_TYPE_LABEL[approval.type]}</Text>
              <Badge tone={APPROVAL_STATUS_TONE[approval.status]}>{APPROVAL_STATUS_LABEL[approval.status]}</Badge>
            </div>
            <Text as="span" className="muted" size={200}>
              Jumlah {approval.amount} · diminta {approval.requesterName} · berakhir{' '}
              {new Date(approval.expiresAt).toLocaleString('id-ID')}
            </Text>
            {approval.emergencyReason && (
              <Text as="span" className="error" size={200}>Darurat: {approval.emergencyReason}</Text>
            )}
            {approval.decisions.length > 0 && (
              <div className="stack" style={{ gap: '0.15rem' }}>
                {approval.decisions.map((decision) => (
                  <Text as="span" className="muted" size={200} key={decision.decisionId}>
                    Tier {decision.tier} · {decision.approverName} ·{' '}
                    {decision.decision === 'APPROVE' ? 'menyetujui' : 'menolak'}
                    {decision.reason ? ` — ${decision.reason}` : ''}
                  </Text>
                ))}
              </div>
            )}
            <TextareaField
              label="Catatan keputusan"
              value={note}
              rows={2}
              disabled={!canDecide || busy}
              onChange={(_, data) => setNotes((current) => ({ ...current, [approval.approvalId]: data.value }))}
            />
            <div className="row wrap">
              <Button variant="primary" disabled={!canDecide || busy} onClick={() => void decide(approval, 'APPROVE')}>
                Setujui
              </Button>
              {/* Penolakan WAJIB beralasan: permintaan yang ditolak tanpa keterangan akan
                  diajukan ulang persis sama oleh pemohon, dan antrean berputar tanpa ada yang
                  tahu apa yang harus diperbaiki. */}
              <Button
                variant="danger"
                disabled={!canDecide || busy || !note.trim()}
                onClick={() => void decide(approval, 'REJECT')}
              >
                Tolak
              </Button>
              {!note.trim() && <Text as="span" className="muted" size={200}>Isi catatan untuk menolak.</Text>}
            </div>
          </article>
        )
      })}
    </div>
  )
}

// ————————————————————————————— kebijakan —————————————————————————————

function ApprovalPolicySection({ reference }: { reference: WarehouseReference }) {
  const toast = useToast()
  const [policies, setPolicies] = useState<readonly ApprovalPolicyView[]>([])
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setPolicies(await listApprovalPolicies())
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.message : 'Gagal memuat kebijakan persetujuan')
      setPolicies([])
    } finally {
      setLoading(false)
    }
  }, [toast])

  useEffect(() => {
    void load()
  }, [load])

  if (loading) return <div className="card"><SkeletonRows rows={4} cols={3} /></div>
  if (policies.length === 0) {
    return <div className="card"><EmptyState title="Kebijakan tidak tersedia" /></div>
  }

  return (
    <div className="stack">
      {policies.map((policy) => (
        <PolicyCard key={policy.type} policy={policy} reference={reference} onSaved={load} />
      ))}
    </div>
  )
}

/** Draft tier yang disunting layar. `roleHolderIds` ikut dibawa HANYA untuk ditampilkan. */
interface TierDraft {
  number: number
  minimumAmount: string
  approverRole: string
  approverIds: string[]
  readonly roleHolderIds: readonly string[]
}

function PolicyCard({
  policy,
  reference,
  onSaved,
}: {
  policy: ApprovalPolicyView
  reference: WarehouseReference
  onSaved: () => Promise<void>
}) {
  const { can } = useCan()
  const toast = useToast()
  const canManage = can('inventory.approval.manage')
  const [tiers, setTiers] = useState<TierDraft[]>(() => policy.tiers.map(toDraft))
  const [expiryHours, setExpiryHours] = useState(String(policy.expiryHours))
  const [emergencyAllowed, setEmergencyAllowed] = useState(policy.emergencyAllowed)
  const [busy, setBusy] = useState(false)

  const save = async () => {
    setBusy(true)
    try {
      await saveApprovalPolicy(policy.type, {
        // `roleHolderIds` SENGAJA tidak ikut dikirim. Daftar itu hasil resolusi `approverRole`
        // oleh modul iam; mengirimnya balik akan membekukan pemegang peran hari ini menjadi
        // daftar nama permanen — orang yang perannya dicabut besok tetap bisa menyetujui hapus
        // buku, dan tidak ada satu layar pun yang menunjukkan kenapa.
        tiers: tiers.map((tier) => ({
          number: tier.number,
          minimumAmount: Number(tier.minimumAmount) || 0,
          approverRole: tier.approverRole.trim(),
          approverIds: tier.approverIds,
        })),
        expiryHours: Number(expiryHours) || 24,
        emergencyAllowed,
      })
      await onSaved()
      toast.success('Kebijakan persetujuan disimpan')
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.message : 'Kebijakan tidak dapat disimpan')
    } finally {
      setBusy(false)
    }
  }

  const invalid = tiers.some((tier) => !tier.approverRole.trim())

  return (
    <article className="card stack" aria-label={`Kebijakan ${APPROVAL_TYPE_LABEL[policy.type]}`}>
      <div className="spread wrap">
        <Text as="strong">{APPROVAL_TYPE_LABEL[policy.type]}</Text>
        {policy.configured ? (
          <Badge tone="good">Siap dipakai</Badge>
        ) : (
          // Tanpa penanda ini, administrator baru melihat matriks bawaan yang terlihat lengkap
          // dan baru tahu tipe ini belum bisa dipakai saat permintaan pertama DITOLAK server.
          <Badge tone="critical">Belum siap — permintaan akan ditolak</Badge>
        )}
      </div>

      {tiers.map((tier, index) => (
        <TierEditor
          key={tier.number}
          tier={tier}
          reference={reference}
          canManage={canManage}
          onChange={(next) => setTiers((current) => current.map((entry, position) => (position === index ? next : entry)))}
        />
      ))}

      <div className="row wrap" style={{ alignItems: 'flex-end' }}>
        <TextField
          label="Kedaluwarsa (jam)"
          value={expiryHours}
          disabled={!canManage}
          onChange={(_, data) => setExpiryHours(data.value)}
        />
        <SelectField
          label="Override darurat"
          value={emergencyAllowed ? 'yes' : 'no'}
          disabled={!canManage}
          onChange={(_, data) => setEmergencyAllowed(data.value === 'yes')}
        >
          <option value="no">Tidak diizinkan</option>
          <option value="yes">Diizinkan</option>
        </SelectField>
        {canManage && (
          <Button variant="primary" disabled={busy || invalid} onClick={() => void save()}>
            {busy ? 'Menyimpan…' : 'Simpan kebijakan'}
          </Button>
        )}
      </div>
    </article>
  )
}

/**
 * Satu tier, dengan DUA daftar penyetuju yang tampil TERPISAH.
 *
 * Ini bukan pilihan tata letak. `approverIds` ditunjuk administrator dan boleh ia hapus di
 * sini; `roleHolderIds` datang dari siapa yang memegang `approverRole` di modul iam dan TIDAK
 * bisa dihapus dari layar ini. Kalau keduanya digabung jadi satu daftar, tombol hapus muncul
 * di sebelah nama yang tak bisa dihapus dari sini — administrator menekannya, menyimpan,
 * namanya kembali lagi, dan ia menyimpulkan penyimpanannya gagal.
 */
function TierEditor({
  tier,
  reference,
  canManage,
  onChange,
}: {
  tier: TierDraft
  reference: WarehouseReference
  canManage: boolean
  onChange: (next: TierDraft) => void
}) {
  const [pick, setPick] = useState('')
  const effectiveEmpty = tier.approverIds.length === 0 && tier.roleHolderIds.length === 0

  const addable = useMemo(
    () => reference.users.filter((user) => !tier.approverIds.includes(user.id)),
    [reference.users, tier.approverIds],
  )

  return (
    <section className="card stack" aria-label={`Tier ${tier.number}`} style={{ gap: '0.6rem' }}>
      <div className="spread wrap">
        <Text as="strong" size={300}>Tier {tier.number}</Text>
        {effectiveEmpty && <Badge tone="critical">Tier belum siap — tanpa penyetuju</Badge>}
      </div>
      <div className="row wrap" style={{ alignItems: 'flex-end' }}>
        <TextField
          label="Ambang minimum"
          value={tier.minimumAmount}
          disabled={!canManage}
          onChange={(_, data) => onChange({ ...tier, minimumAmount: data.value })}
        />
        <TextField
          label="Peran penyetuju"
          value={tier.approverRole}
          disabled={!canManage}
          onChange={(_, data) => onChange({ ...tier, approverRole: data.value })}
        />
      </div>

      <div className="stack" role="group" aria-label={`Penyetuju ditunjuk tier ${tier.number}`} style={{ gap: '0.25rem' }}>
        <Text as="span" weight="semibold" size={200}>Penyetuju yang ditunjuk</Text>
        {tier.approverIds.length === 0 ? (
          <Text as="span" className="muted" size={200}>Belum ada nama yang ditunjuk.</Text>
        ) : (
          tier.approverIds.map((approverId) => (
            <div className="spread wrap" key={approverId}>
              <Text as="span">{reference.names.user(approverId)}</Text>
              {canManage && (
                <Button
                  variant="danger"
                  size="small"
                  aria-label={`Hapus penyetuju ${reference.names.user(approverId)}`}
                  onClick={() =>
                    onChange({ ...tier, approverIds: tier.approverIds.filter((entry) => entry !== approverId) })
                  }
                >
                  Hapus
                </Button>
              )}
            </div>
          ))
        )}
        {canManage && (
          <div className="row wrap" style={{ alignItems: 'flex-end' }}>
            <SelectField
              label="Tambah penyetuju"
              value={pick}
              onChange={(_, data) => setPick(data.value)}
            >
              <option value="">Pilih pengguna…</option>
              {addable.map((user) => (
                <option key={user.id} value={user.id}>{user.name}</option>
              ))}
            </SelectField>
            <Button
              disabled={!pick}
              onClick={() => {
                onChange({ ...tier, approverIds: [...tier.approverIds, pick] })
                setPick('')
              }}
            >
              Tambah
            </Button>
          </div>
        )}
      </div>

      <div className="stack" role="group" aria-label={`Pemegang peran tier ${tier.number}`} style={{ gap: '0.25rem' }}>
        <Text as="span" weight="semibold" size={200}>
          Pemegang peran {tier.approverRole || '—'}
        </Text>
        <Text as="span" className="muted" size={200}>
          Diresolusi otomatis dari modul pengguna. Tidak bisa dihapus di sini — ubah lewat penugasan peran di
          pengaturan pengguna.
        </Text>
        {tier.roleHolderIds.length === 0 ? (
          <Text as="span" className="muted" size={200}>Belum ada pengguna yang memegang peran ini.</Text>
        ) : (
          tier.roleHolderIds.map((holderId) => (
            <Text as="span" key={holderId}>{reference.names.user(holderId)}</Text>
          ))
        )}
      </div>
    </section>
  )
}

function toDraft(tier: ApprovalTierView): TierDraft {
  return {
    number: tier.number,
    minimumAmount: String(tier.minimumAmount),
    approverRole: tier.approverRole,
    approverIds: [...tier.approverIds],
    roleHolderIds: tier.roleHolderIds,
  }
}

// ————————————————————————————— override darurat —————————————————————————————

function EmergencyOverrideSection() {
  const toast = useToast()
  const [overrides, setOverrides] = useState<readonly EmergencyOverrideView[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    void (async () => {
      try {
        setOverrides(await listEmergencyOverrides())
      } catch (caught) {
        toast.error(caught instanceof ApiError ? caught.message : 'Gagal memuat override darurat')
      } finally {
        setLoading(false)
      }
    })()
  }, [toast])

  if (loading) return <div className="card"><SkeletonRows rows={3} cols={4} /></div>
  if (overrides.length === 0) {
    return (
      <div className="card">
        <EmptyState title="Belum ada override darurat" hint="Kontrol empat-mata belum pernah dilangkahi." />
      </div>
    )
  }

  return (
    <div className="stack">
      {overrides.map((entry) => (
        <article className="card stack" key={entry.approvalId}>
          <div className="spread wrap">
            <Text as="strong">{APPROVAL_TYPE_LABEL[entry.type]}</Text>
            <Badge tone="serious">Tier dilangkahi: {entry.bypassedTiers.join(', ') || '—'}</Badge>
          </div>
          <Text as="span" className="muted" size={200}>
            Jumlah {entry.amount} · {entry.requesterName} ·{' '}
            {new Date(entry.occurredAt).toLocaleString('id-ID')}
          </Text>
          <Text as="span">{entry.reason}</Text>
        </article>
      ))}
    </div>
  )
}
