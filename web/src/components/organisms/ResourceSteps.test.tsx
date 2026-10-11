import { useState } from 'react'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import { Blade } from './Blade'
import { ResourceForm, useResourceReviewLock } from './ResourceForm'
import { ResourceStep } from './ResourceStep'
import { useCreationReview } from './CreationReview'

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
