import { useState } from 'react'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import { Blade } from './Blade'
import { ResourceForm, useResourceReviewLock } from './ResourceForm'
import { ResourceStep } from './ResourceStep'
import { useCreationReview } from './CreationReview'
import { WarehousePicker } from './warehouse/WarehousePicker'

vi.mock('@/system', () => ({ useConfirm: () => vi.fn() }))

const steps = [{ key: 'identity', label: 'Identitas' }, { key: 'location', label: 'Lokasi' }]
function Fields() {
  return <><ResourceStep step="identity"><label>Nama<input required name="name" /></label></ResourceStep>
    <ResourceStep step="location"><label>Alamat<input required name="address" /></label></ResourceStep></>
}
it('validates preceding steps, retains edits, and submits through a domain review exactly once', async () => {
  const saved = vi.fn(), user = userEvent.setup()
  function Example() {
    const creation = useCreationReview(true)
    const prepare = () => { if (!creation.beforeSave()) { saved(); creation.finish() } }
    return <Blade open title="Tambah pelanggan" onClose={() => {}}
      creation={{ ...creation, steps, prepare: () => (document.getElementById('form') as HTMLFormElement).requestSubmit(), summary: <p>Ringkasan pelanggan</p> }}
      footer={<button onClick={prepare}>Buat pelanggan</button>}>
      <form id="form" onSubmit={event => { event.preventDefault(); prepare() }}><Fields /></form>
    </Blade>
  }
  render(<Example />)
  await user.click(screen.getByRole('tab', { name: 'Lokasi' }))
  expect(screen.getByRole('tab', { name: 'Identitas' }).getAttribute('aria-selected')).toBe('true')
  await user.type(screen.getByLabelText('Nama'), 'Budi')
  fireEvent.submit(document.getElementById('form')!)
  expect(screen.getByRole('tab', { name: 'Lokasi' }).getAttribute('aria-selected')).toBe('true')
  expect(saved).not.toHaveBeenCalled()
  await user.type(screen.getByLabelText('Alamat'), 'Cimahi')
  await user.click(screen.getByRole('tab', { name: 'Identitas' }))
  expect(screen.getByLabelText('Nama')).toHaveProperty('value', 'Budi')
  await user.click(screen.getByRole('tab', { name: 'Tinjau + buat' }))
  expect(screen.getByText('Ringkasan pelanggan')).toBeTruthy()
  expect(saved).not.toHaveBeenCalled()
  await user.click(screen.getByRole('button', { name: 'Buat pelanggan' }))
  expect(saved).toHaveBeenCalledTimes(1)
})
it('reveals an invalid hidden field before review and keeps uncertain warehouse submissions locked', async () => {
  const user = userEvent.setup()
  function LockedReview() { useResourceReviewLock(true); return <p>Menunggu hasil transaksi</p> }
  function Example() {
    const [review, setReview] = useState(false)
    return <ResourceForm title="Penerimaan" steps={steps} onClose={() => {}} onBack={() => setReview(false)}
      onReview={() => setReview(true)} review={review ? <LockedReview /> : undefined}><Fields /></ResourceForm>
  }
  render(<Example />)
  await user.type(screen.getByLabelText('Nama'), 'Penerimaan')
  await user.click(screen.getByRole('tab', { name: 'Tinjau + buat' }))
  await waitFor(() => expect(document.activeElement).toBe(screen.getByLabelText('Alamat')))
  expect(screen.getByRole('tab', { name: 'Lokasi' }).getAttribute('aria-selected')).toBe('true')
  await user.type(screen.getByLabelText('Alamat'), 'Gudang')
  await user.click(screen.getByRole('tab', { name: 'Tinjau + buat' }))
  expect(screen.getByRole('tab', { name: 'Identitas' })).toHaveProperty('disabled', true)
  await user.click(screen.getByRole('button', { name: 'Tutup' }))
  expect(screen.getByText('Menunggu hasil transaksi')).toBeTruthy()
})

it('requires an actual lookup selection and applies domain validation before advancing', async () => {
  const user = userEvent.setup(), onReview = vi.fn()
  const location = { id: 'warehouse-1', name: 'Gudang Utama' }
  const load = vi.fn(async () => ({ items: [location], page: 0, size: 25, totalElements: 1 }))
  function Example() {
    const [selected, setSelected] = useState<typeof location | null>(null), [latitude, setLatitude] = useState('91')
    return <ResourceForm title="Lokasi barang" onClose={() => {}} onBack={() => {}} onReview={onReview}
      steps={[{ key: 'location', label: 'Lokasi', validate: () => Number(latitude) > 90 ? 'Lintang maksimal 90.' : null }, { key: 'items', label: 'Barang' }]}>
      <ResourceStep step="location"><WarehousePicker label="Gudang" load={load} value={selected} onChange={setSelected} name={row => row.name} />
        <label>Lintang<input value={latitude} onChange={event => setLatitude(event.target.value)} /></label></ResourceStep>
      <ResourceStep step="items"><p>Pilih barang di gudang.</p></ResourceStep>
    </ResourceForm>
  }
  render(<Example />)
  const picker = await screen.findByRole('combobox', { name: 'Gudang' })
  await waitFor(() => expect(picker).not.toHaveProperty('disabled', true))
  await user.type(picker, 'Gudang')
  await user.click(screen.getByRole('button', { name: 'Berikutnya: Barang' }))
  expect(screen.getByRole('tab', { name: 'Lokasi' }).getAttribute('aria-selected')).toBe('true')
  expect(onReview).not.toHaveBeenCalled()
  await user.click(picker)
  await waitFor(() => expect(screen.getByRole('option', { name: 'Gudang Utama' }).getAttribute('aria-disabled')).not.toBe('true'))
  await user.click(screen.getByRole('option', { name: 'Gudang Utama' }))
  await user.click(screen.getByRole('button', { name: 'Berikutnya: Barang' }))
  expect(screen.getByRole('alert').textContent).toContain('Lintang maksimal 90.')
  await user.clear(screen.getByLabelText('Lintang')); await user.type(screen.getByLabelText('Lintang'), '-6.87')
  await user.click(screen.getByRole('button', { name: 'Berikutnya: Barang' }))
  expect(screen.getByRole('tab', { name: 'Barang' }).getAttribute('aria-selected')).toBe('true')
  expect(onReview).not.toHaveBeenCalled()
  await user.click(screen.getByRole('button', { name: 'Tinjau + buat' }))
  expect(onReview).toHaveBeenCalledTimes(1)
})
