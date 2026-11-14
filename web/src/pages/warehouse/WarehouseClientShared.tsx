import { useCallback, useId, useState, type FormEvent, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { createLoan, createPurchase, CLIENT_LABELS, type ClientDetail, type Loan, type Purchase, type Warranty } from '@/api/warehouse/clientOperations'
import type { WarehouseLocation, WarehouseSku, WarehouseSupplier } from '@/api/warehouse/models'
import { referenceLocations, referenceSkus, referenceSuppliers } from '@/api/warehouse/reference'
import { getReferenceSku } from '@/api/warehouse/referenceCatalog'
import { listReferenceRequests, type ReferenceRequest } from '@/api/warehouse/referenceRequests'
import { quantityFromInput, formatBaseQuantity } from '@/api/warehouse/quantity'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { Button, EmptyState, SelectField, TextareaField, TextField } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { DataTable, type Column } from '@/components/organisms/DataTable'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseQuantity, WarehouseQuantityField } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'

export function ClientList<T extends Purchase | Loan | Warranty>({ title, subtitle, load, columns, create, onOpen }: { title: string; subtitle: string; load: (search: string, page: number) => Promise<{ items: T[]; page: number; size: number; totalElements: number }>; columns: Column<T>[]; create?: ReactNode; onOpen: (id: string) => void }) {
  const [search, setSearch] = useState(''), [page, setPage] = useState(0)
  const result = useWarehouseQuery(useCallback(() => load(search, page), [load, search, page]))
  return <div className="stack"><PageHeader title={title} subtitle={subtitle} actions={create} />
    <div className="row wrap"><TextField label="Cari dokumen" value={search} maxLength={200} onChange={(_, d) => { setSearch(d.value); setPage(0) }} /><Button onClick={result.reload}>Muat ulang</Button></div>
    <WarehouseState {...result}>{data => <><DataTable presentation="warehouse" rows={data.items} rowKey={row => row.id} empty={<EmptyState title="Belum ada dokumen" hint="Dokumen dalam akses gudang Anda akan muncul di sini." />} columns={[...columns,
      { key: 'state', header: 'Status', cell: row => CLIENT_LABELS[row.state] }, { key: 'warehouse', header: 'Gudang', cell: row => row.warehouseName },
      { key: 'open', header: 'Tindakan', cell: row => <Button onClick={() => onOpen(row.id)}>Buka {row.id.slice(-8)}</Button> },
    ]} /><WarehousePagination page={page} size={data.size} total={data.totalElements} onChange={setPage} /></>}</WarehouseState>
  </div>
}

export function ClientTimeline({ events }: { events: ClientDetail<Purchase>['timeline'] }) {
  const labels: Record<string, string> = { CREATE: 'Dibuat', REVIEW: 'Ditinjau', DECIDE: 'Keputusan', RECEIVE: 'Diterima', CANCEL: 'Sisa dibatalkan', ISSUE: 'Diserahkan', RETURN: 'Dikembalikan', DISPATCH: 'Dikirim ke vendor', INSPECT: 'Diperiksa' }
  return <div className="stack"><h3>Riwayat</h3>{events.map(event => <div key={event.id}><strong>{labels[event.action] ?? event.action}</strong> · {event.actorName} · {new Date(event.recordedAt).toLocaleString('id-ID')}<p>{event.notes}</p>{event.movementIds.map(id => <Link key={id} to={'/warehouse/stock?movementId=' + id}>Mutasi {id.slice(-8)} </Link>)}</div>)}</div>
}

export function ClientCommandForm<T>({ title, children, prepare, summary, onDone, onClose }: { title: string; children: ReactNode; prepare: () => WarehouseCommand<T>; summary: ReactNode; onDone: (v: T) => void; onClose: () => void }) {
  const id = useId(), [review, setReview] = useState<WarehouseCommand<T> | null>(null), [error, setError] = useState('')
  function submit(e: FormEvent) { e.preventDefault(); try { setReview(prepare()); setError('') } catch (e) { setError(e instanceof Error ? e.message : 'Periksa isian.') } }
  return <ResourceForm title={title} onClose={onClose} onBack={() => setReview(null)} footer={<><Button onClick={onClose}>Batal</Button><Button form={id} type="submit" variant="primary">Tinjau tindakan</Button></>} review={review && <WarehouseCommandDialog embedded title={title} command={review} summary={summary} onDone={onDone} onClose={() => setReview(null)} />}>
    <form id={id} className="stack" onSubmit={submit}>{children}{error && <p role="alert" className="error">{error}</p>}</form>
  </ResourceForm>
}

interface DraftItem { key: string; sku: WarehouseSku | null; quantity: string; requestLineId?: string; price: string }
const empty = (): DraftItem => ({ key: crypto.randomUUID(), sku: null, quantity: '', price: '' })
export function ClientCreateEditor({ kind, initialRequest, onClose, onSaved }: { kind: 'PURCHASE' | 'LOAN'; initialRequest?: ReferenceRequest; onClose: () => void; onSaved: (v: Purchase | Loan) => void }) {
  const [warehouse, setWarehouse] = useState<WarehouseLocation | null>(null), [supplier, setSupplier] = useState<WarehouseSupplier | null>(null)
  const [request, setRequest] = useState<ReferenceRequest | null>(null), [rows, setRows] = useState<DraftItem[]>([empty()]), [loading, setLoading] = useState(false), [error, setError] = useState('')
  const [name, setName] = useState(''), [contact, setContact] = useState(''), [date, setDate] = useState(new Date().toLocaleDateString('en-CA')), [due, setDue] = useState(''), [notes, setNotes] = useState('')
  const requests = useCallback(async (search: string, page: number) => listReferenceRequests(search, page), [])
  async function chooseRequest(next: ReferenceRequest | null) {
    setLoading(true); setError('')
    try {
      const items = next ? await Promise.all(next.lines.filter(line => line.skuId && BigInt(line.approvedBase) > BigInt(line.receivedBase)).map(async line => ({ key: crypto.randomUUID(), sku: await getReferenceSku(line.skuId!), quantity: formatBaseQuantity((BigInt(line.approvedBase) - BigInt(line.receivedBase)).toString(), line.baseUnit), requestLineId: line.id, price: '' }))) : [empty()]
      setRequest(next); setRows(items)
    } catch (e) { setError(e instanceof Error ? e.message : 'Request tidak dapat dimuat.') } finally { setLoading(false) }
  }
  function prepare() {
    if (!warehouse || kind === 'PURCHASE' && !supplier || !name.trim() || loading) throw new Error('Lengkapi gudang, penerima/pemasok, dan referensi.')
    const lines = rows.map(row => {
      if (!row.sku) throw new Error('Pilih barang setiap baris.')
      if (row.price && !/^(0|[1-9][0-9]{0,18})$/.test(row.price)) throw new Error('Harga harus bilangan rupiah bulat.')
      return { skuId: row.sku.id, baseUnit: row.sku.baseUnit, quantityBase: quantityFromInput(row.quantity, row.sku.baseUnit), ...(row.requestLineId ? { requestLineId: row.requestLineId } : {}), ...(row.price ? { unitPriceMinor: row.price } : {}) }
    })
    if (!lines.length || new Set(lines.map(l => l.skuId)).size !== lines.length) throw new Error('Isi barang berbeda di setiap baris.')
    return kind === 'PURCHASE' ? createPurchase({ warehouseId: warehouse.id, supplierId: supplier!.id, purchaseDate: date, reference: name.trim(), notes, lines, ...(request ? { requestId: request.id } : {}) })
      : createLoan({ warehouseId: warehouse.id, borrowerName: name.trim(), contact, loanDate: date, dueDate: due || null, notes, lines })
  }
  function patch(key: string, patch: Partial<DraftItem>) { setRows(rows => rows.map(row => row.key === key ? { ...row, ...patch } : row)) }
  return <ClientCommandForm<Purchase | Loan> title={kind === 'PURCHASE' ? 'Catat pembelian' : 'Ajukan pinjaman client'} onClose={onClose} onDone={onSaved} prepare={prepare} summary={<><p>{name} · {warehouse?.name} · {supplier?.name}</p>{rows.map(row => <p key={row.key}>{row.sku?.name}: {row.quantity} {row.sku?.baseUnit === 'MM' ? 'm' : 'unit'}</p>)}<p>{notes}</p><p>{kind === 'PURCHASE' ? 'Stok bertambah setelah barang diterima.' : 'Barang diserahkan setelah pinjaman disetujui.'}</p></>}>
    {kind === 'PURCHASE' && <><WarehousePicker optional label="Request pengadaan (opsional)" load={requests} value={request} name={r => r.reason + ' · ' + r.id.slice(-8)} eligible={r => r.kind === 'PROCUREMENT' && ['APPROVED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'PARTIALLY_FULFILLED'].includes(r.state)} onChange={r => void chooseRequest(r)} />{initialRequest && !request && <Button type="button" disabled={loading} onClick={() => void chooseRequest(initialRequest)}>Gunakan request {initialRequest.id.slice(-8)}</Button>}</>}
    <WarehousePicker label="Gudang" load={referenceLocations} value={warehouse} name={r => r.name ?? r.code} eligible={r => r.state === 'ACTIVE' && r.issueEligible && (!request?.warehouseId || request.warehouseId === r.id)} onChange={setWarehouse} />
    {kind === 'PURCHASE' && <WarehousePicker label="Pemasok" load={referenceSuppliers} value={supplier} name={r => r.name} eligible={r => r.state === 'ACTIVE'} onChange={setSupplier} />}
    <TextField label={kind === 'PURCHASE' ? 'Nomor / referensi pesanan' : 'Nama client / peminjam'} required maxLength={kind === 'PURCHASE' ? 500 : 200} value={name} onChange={(_, d) => setName(d.value)} />
    {kind === 'LOAN' && <TextField label="Kontak peminjam" maxLength={300} value={contact} onChange={(_, d) => setContact(d.value)} />}
    <TextField type="date" label={kind === 'PURCHASE' ? 'Tanggal pembelian' : 'Tanggal pinjaman'} required value={date} onChange={(_, d) => setDate(d.value)} />
    {kind === 'LOAN' && <TextField type="date" label="Batas pengembalian (opsional)" value={due} onChange={(_, d) => setDue(d.value)} />}
    {loading ? <p>Memuat barang request…</p> : rows.map((row, index) => <fieldset className="card stack" key={row.key}><legend>Barang {index + 1}</legend>
      {request ? <p>{row.sku?.name}</p> : <WarehousePicker label={'Barang ' + (index + 1)} load={referenceSkus} value={row.sku} name={r => r.name + ' · ' + r.code} eligible={r => r.state === 'ACTIVE'} onChange={sku => patch(row.key, { sku, quantity: '' })} />}
      {row.sku && <WarehouseQuantityField label={'Jumlah ' + (index + 1)} unit={row.sku.baseUnit} value={row.quantity} onChange={quantity => patch(row.key, { quantity })} />}
      {kind === 'PURCHASE' && <TextField label="Harga satuan rupiah (opsional)" inputMode="numeric" value={row.price} onChange={(_, d) => patch(row.key, { price: d.value })} />}
      {rows.length > 1 && <Button type="button" onClick={() => setRows(rows => rows.filter(r => r.key !== row.key))}>Hapus barang</Button>}
    </fieldset>)}
    {!request && <Button type="button" disabled={rows.length >= 100} onClick={() => setRows(rows => [...rows, empty()])}>Tambah barang</Button>}
    <TextareaField label="Catatan" maxLength={1000} value={notes} onChange={(_, d) => setNotes(d.value)} />{error && <p className="error" role="alert">{error}</p>}
  </ClientCommandForm>
}

export function ClientLineQuantities({ quantity, done, unit }: { quantity: string; done: string; unit: 'EA' | 'MM' }) { return <p><WarehouseQuantity value={done} unit={unit} /> dari <WarehouseQuantity value={quantity} unit={unit} /></p> }
export function Choice({ label, value, onChange, values }: { label: string; value: string; onChange: (v: string) => void; values: [string, string][] }) { return <SelectField label={label} value={value} onChange={(_, d) => onChange(d.value)}>{values.map(([v, label]) => <option value={v} key={v}>{label}</option>)}</SelectField> }
