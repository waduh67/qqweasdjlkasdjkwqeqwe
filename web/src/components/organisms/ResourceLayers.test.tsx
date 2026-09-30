import { useState } from 'react'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import { ResourceForm } from './ResourceForm'

function Layer({ level, close, submitted }: { level: number; close: () => void; submitted: (level: number) => void }) {
  const [child, setChild] = useState(false), [text, setText] = useState('')
  return <ResourceForm title={`Form ${level}`} onClose={close} onBack={() => {}} footer={<button type="submit" form={`layer-${level}`}>Simpan {level}</button>}>
    <form id={`layer-${level}`} onSubmit={event => { event.preventDefault(); submitted(level); close() }}>
      <label>Nama {level}<input value={text} onChange={event => setText(event.target.value)} /></label>
      <button type="button" onClick={() => setChild(true)}>Buka {level + 1}</button>
      {child && <Layer level={level + 1} close={() => setChild(false)} submitted={submitted} />}
    </form>
  </ResourceForm>
}
function Example({ submitted }: { submitted: (level: number) => void }) {
  const [open, setOpen] = useState(false)
  return <main><h1>Daftar</h1><button onClick={() => setOpen(true)}>Buka 0</button>{open && <Layer level={0} close={() => setOpen(false)} submitted={submitted} />}</main>
}
it('keeps three nested drafts mounted and closes only the current layer with its own discard guard', async () => {
  const user = userEvent.setup(); render(<Example submitted={vi.fn()} />)
  await user.click(screen.getByRole('button', { name: 'Buka 0' }))
  await user.type(screen.getByLabelText('Nama 0'), 'Penerimaan')
  await user.click(screen.getByRole('button', { name: 'Buka 1' }))
  await user.type(screen.getByLabelText('Nama 1'), 'Lokasi')
  const launcher = screen.getByRole('button', { name: 'Buka 2' })
  await user.click(launcher); await user.type(screen.getByLabelText('Nama 2'), 'Gudang induk')
  expect(document.querySelectorAll('[data-resource-layer]')).toHaveLength(3)
  await waitFor(() => expect(screen.getAllByRole('dialog').map(dialog => dialog.getAttribute('aria-labelledby'))).toHaveLength(1))
  await user.keyboard('{Escape}')
  await user.click(await screen.findByRole('button', { name: 'Lanjutkan pengisian' }))
  expect(screen.getByLabelText('Nama 2')).toHaveProperty('value', 'Gudang induk')
  await user.keyboard('{Escape}'); await user.click(await screen.findByRole('button', { name: 'Buang perubahan' }))
  await waitFor(() => expect(document.activeElement).toBe(launcher))
  expect(document.querySelectorAll('[data-resource-layer]')).toHaveLength(2)
  expect(screen.getByLabelText('Nama 1')).toHaveProperty('value', 'Lokasi')
  await user.click(screen.getByRole('button', { name: 'Kembali ke panel sebelumnya' }))
  await user.click(await screen.findByRole('button', { name: 'Buang perubahan' }))
  expect(screen.getByLabelText('Nama 0')).toHaveProperty('value', 'Penerimaan')
})
it('contains child form submission and ignores backdrop clicks without losing the parent draft', async () => {
  const submitted = vi.fn(), user = userEvent.setup(); render(<Example submitted={submitted} />)
  await user.click(screen.getByRole('button', { name: 'Buka 0' })); await user.type(screen.getByLabelText('Nama 0'), 'Tersimpan di induk')
  await user.click(screen.getByRole('button', { name: 'Buka 1' })); await user.type(screen.getByLabelText('Nama 1'), 'Baru')
  const child = screen.getByRole('dialog', { name: 'Form 1' })
  const backdrops = document.querySelectorAll('.resource-form-backdrop'); fireEvent.click(backdrops[backdrops.length - 1])
  expect(screen.getByRole('dialog', { name: 'Form 1' })).toBe(child)
  await user.click(within(child).getByRole('button', { name: 'Simpan 1' }))
  expect(submitted).toHaveBeenCalledExactlyOnceWith(1)
  expect(screen.getByLabelText('Nama 0')).toHaveProperty('value', 'Tersimpan di induk')
})
