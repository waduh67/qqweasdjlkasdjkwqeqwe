import { useCallback, useEffect, useMemo, useState } from 'react'
import { Text } from '@fluentui/react-components'
import { ApiError } from '@/api/client'
import {
  approveCycleCount,
  createCycleCount,
  getVarianceReport,
  listOpenCounts,
  listStockBalances,
  operationEnvelope,
  type CycleCount,
  type StockBalanceView,
  type VarianceReportView,
} from '@/api/inventory'
import { useCan } from '@/auth/useCan'
import { Badge, Button, EmptyState, SelectField, TextField, TextareaField } from '@/components/atoms'
import { DataTable, type Column } from '@/components/organisms'
import { useToast } from '@/system'
import type { WarehouseReference } from './WarehouseOperationsPage'
import { DISCREPANCY_STATE_LABEL, INVENTORY_STATUS_LABEL, LOCATION_KIND_LABEL } from './WarehouseLabels'

/**
 * Stock opname: hitung fisik, lalu selisihnya diputuskan orang lain.
 *
 * Yang dicatat petugas adalah jumlah yang DILIHAT di rak, bukan selisihnya — selisih dihitung
 * server terhadap saldo saat itu. Kalau layar meminta selisih, petugas harus mengurangi
 * sendiri terhadap angka yang mungkin sudah berubah sejak ia mulai menghitung, dan koreksinya
 * malah menambah selisih baru.
 */
export function WarehouseCountPanel({ reference }: { reference: WarehouseReference }) {
  const { can } = useCan()
  const toast = useToast()
  const [counts, setCounts] = useState<readonly CycleCount[]>([])
  const [variance, setVariance] = useState<VarianceReportView | null>(null)
  const [balances, setBalances] = useState<readonly StockBalanceView[]>([])
  const [loading, setLoading] = useState(true)
  const [deciding, setDeciding] = useState<string | null>(null)

  const canPerform = can('inventory.count.perform')
  const canApprove = can('inventory.count.approve')
  const canReadMovements = can('inventory.movement.view')

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [nextCounts, nextVariance, nextBalances] = await Promise.all([
        canPerform ? listOpenCounts() : Promise.resolve([] as CycleCount[]),
        canReadMovements ? getVarianceReport() : Promise.resolve(null),
        canReadMovements ? listStockBalances() : Promise.resolve([] as StockBalanceView[]),
      ])
      setCounts(nextCounts)
      setVariance(nextVariance)
      setBalances(nextBalances)
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.message : 'Gagal memuat data opname')
    } finally {
      setLoading(false)
    }
  }, [canPerform, canReadMovements, toast])

  useEffect(() => {
    void load()
  }, [load])

  const approve = async (count: CycleCount) => {
    setDeciding(count.countId)
    try {
      const envelope = await operationEnvelope({ countId: count.countId, decision: 'APPROVE' })
      await approveCycleCount(count.countId, envelope)
      toast.success('Selisih disetujui — saldo dikoreksi')
      await load()
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.message : 'Persetujuan selisih gagal')
    } finally {
      setDeciding(null)
    }
  }

  const { names } = reference

  const columns: Column<CycleCount>[] = [
    {
      key: 'item',
      header: 'Item',
      sortValue: (row) => names.itemName(row.itemId),
      cell: (row) => (
        <div className="stack" style={{ gap: 0 }}>
          <Text as="span">{names.itemName(row.itemId)}</Text>
          <Text as="span" className="muted" size={200}>{names.location(row.locationId)}</Text>
        </div>
      ),
    },
    {
      key: 'prior',
      header: 'Saldo sistem',
      align: 'right',
      sortValue: (row) => row.priorQuantity,
      cell: (row) => <Text as="span" className="tnum">{row.priorQuantity}</Text>,
    },
    {
      key: 'observed',
      header: 'Hasil hitung',
      align: 'right',
      sortValue: (row) => row.observedQuantity,
      cell: (row) => <Text as="span" className="tnum">{row.observedQuantity}</Text>,
    },
    {
      key: 'delta',
      header: 'Selisih',
      align: 'right',
      sortValue: (row) => row.observedQuantity - row.priorQuantity,
      cell: (row) => {
        const delta = row.observedQuantity - row.priorQuantity
        return (
          <Badge tone={delta === 0 ? 'neutral' : delta > 0 ? 'good' : 'critical'}>
            {delta > 0 ? `+${delta}` : String(delta)}
          </Badge>
        )
      },
    },
    {
      key: 'state',
      header: 'Status',
      cell: (row) => <Badge tone="warning">{DISCREPANCY_STATE_LABEL[row.discrepancy]}</Badge>,
    },
    {
      key: 'counter',
      header: 'Penghitung',
      cell: (row) => (
        <div className="stack" style={{ gap: 0 }}>
          <Text as="span">{names.user(row.custodianId)}</Text>
          <Text as="span" className="muted" size={200}>{row.reason}</Text>
        </div>
      ),
    },
    {
      key: 'action',
      header: '',
      width: '1%',
      cell: (row) => (
        // Tombol setuju SENGAJA tidak sekadar disembunyikan saat izin kurang: penghitung
        // perlu tahu barisnya menunggu orang lain, bukan mengira formulirnya rusak.
        <Button
          variant="primary"
          disabled={!canApprove || deciding === row.countId}
          onClick={() => void approve(row)}
        >
          {canApprove ? 'Setujui selisih' : 'Menunggu penyetuju'}
        </Button>
      ),
    },
  ]

  return (
    <div className="stack" style={{ gap: '1rem' }}>
      {canPerform && <CountForm reference={reference} balances={balances} onSaved={load} />}

      <section className="stack" style={{ gap: '0.5rem' }}>
        <Text as="h3" weight="semibold">Selisih menunggu keputusan</Text>
        <DataTable
          columns={columns}
          rows={[...counts]}
          rowKey={(row) => row.countId}
          loading={loading}
          presentation="resource"
          empty={
            <EmptyState
              title="Tidak ada selisih terbuka"
              hint={canPerform ? 'Hasil hitung yang sama dengan saldo tidak masuk daftar ini.' : 'Butuh izin melakukan opname untuk melihat daftar ini.'}
            />
          }
        />
      </section>

      {variance && variance.anomalies.length > 0 && (
        <section className="card stack" style={{ gap: '0.5rem' }}>
          <Text as="h3" weight="semibold">Saldo janggal</Text>
          <Text as="span" className="muted" size={200}>
            Baris yang saldonya tidak masuk akal terhadap unit berserial yang tercatat. Perlu opname fisik
            lebih dulu, bukan penyesuaian langsung.
          </Text>
          {variance.anomalies.map((anomaly) => (
            <div className="spread wrap" key={`${anomaly.itemId}:${anomaly.locationId}:${anomaly.status}`}>
              <Text as="span">{names.itemName(anomaly.itemId)}</Text>
              <Text as="span" className="muted" size={200}>
                {anomaly.locationCode} · {INVENTORY_STATUS_LABEL[anomaly.status]} · saldo {anomaly.projectedQuantity} vs{' '}
                {anomaly.serializedAssetCount} unit berserial
              </Text>
              <Badge tone="critical">{anomaly.issue}</Badge>
            </div>
          ))}
        </section>
      )}
    </div>
  )
}

function CountForm({
  reference,
  balances,
  onSaved,
}: {
  reference: WarehouseReference
  balances: readonly StockBalanceView[]
  onSaved: () => Promise<void>
}) {
  const toast = useToast()
  const [locationId, setLocationId] = useState('')
  const [itemId, setItemId] = useState('')
  const [custodianId, setCustodianId] = useState('')
  const [observed, setObserved] = useState('')
  const [reason, setReason] = useState('')
  const [evidence, setEvidence] = useState('')
  const [busy, setBusy] = useState(false)

  const rowsHere = useMemo(
    () => balances.filter((row) => row.locationId === locationId && row.status === 'AVAILABLE'),
    [balances, locationId],
  )
  const current = rowsHere.find((row) => row.itemId === itemId)

  const observedNumber = Number(observed)
  const problems: string[] = []
  if (!locationId) problems.push('Lokasi wajib dipilih.')
  if (!itemId) problems.push('Item wajib dipilih.')
  if (!custodianId) problems.push('Pemegang custody wajib dipilih.')
  if (observed.trim() === '' || Number.isNaN(observedNumber) || observedNumber < 0) {
    problems.push('Hasil hitung harus angka nol atau lebih.')
  }
  if (!reason.trim()) problems.push('Alasan wajib diisi.')
  if (!evidence.trim()) problems.push('Referensi bukti wajib diisi.')

  const submit = async () => {
    setBusy(true)
    try {
      const payload = { locationId, itemId, observedQuantity: observedNumber, custodianId }
      const envelope = await operationEnvelope(payload)
      await createCycleCount({
        ...payload,
        reason: reason.trim(),
        evidenceReference: evidence.trim(),
        ...envelope,
      })
      toast.success('Hasil hitung tersimpan')
      setObserved('')
      setReason('')
      setEvidence('')
      await onSaved()
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.message : 'Hasil hitung tidak tersimpan')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="card stack" style={{ gap: '0.85rem' }}>
      <Text as="h3" weight="semibold">Catat hasil hitung</Text>
      <div className="row wrap" style={{ alignItems: 'flex-end' }}>
        <SelectField
          label="Lokasi"
          value={locationId}
          onChange={(_, data) => {
            setLocationId(data.value)
            setItemId('')
            setCustodianId('')
          }}
        >
          <option value="">Pilih lokasi…</option>
          {reference.locations.map((location) => (
            <option key={location.id} value={location.id}>
              {location.code} · {LOCATION_KIND_LABEL[location.kind]}
            </option>
          ))}
        </SelectField>
        <SelectField
          label="Item"
          value={itemId}
          onChange={(_, data) => {
            setItemId(data.value)
            const row = rowsHere.find((entry) => entry.itemId === data.value)
            if (row) setCustodianId(row.custodyOwnerId)
          }}
        >
          <option value="">Pilih item…</option>
          {reference.items
            .filter((item) => item.active)
            .map((item) => (
              <option key={item.id} value={item.id}>{item.name} ({item.code})</option>
            ))}
        </SelectField>
        <SelectField label="Pemegang custody" value={custodianId} onChange={(_, data) => setCustodianId(data.value)}>
          <option value="">Pilih pemegang…</option>
          {reference.users.map((user) => (
            <option key={user.id} value={user.id}>{user.name}</option>
          ))}
        </SelectField>
        <TextField
          label="Hasil hitung fisik"
          value={observed}
          hint={current ? `Saldo sistem saat ini ${current.quantity}` : 'Belum ada saldo tercatat di lokasi ini'}
          onChange={(_, data) => setObserved(data.value)}
        />
      </div>
      <TextField
        label="Referensi bukti"
        required
        value={evidence}
        hint="Nomor berita acara atau tautan foto hitungan. Penyetuju memutuskan dari sini, bukan dari ingatan."
        onChange={(_, data) => setEvidence(data.value)}
      />
      <TextareaField label="Alasan" required rows={2} value={reason} onChange={(_, data) => setReason(data.value)} />
      {problems.length > 0 && (
        <div className="stack" style={{ gap: '0.15rem' }} role="alert">
          {problems.map((problem) => (
            <Text as="span" className="error" size={200} key={problem}>{problem}</Text>
          ))}
        </div>
      )}
      <div>
        <Button variant="primary" disabled={busy || problems.length > 0} onClick={() => void submit()}>
          {busy ? 'Menyimpan…' : 'Simpan hasil hitung'}
        </Button>
      </div>
    </div>
  )
}
