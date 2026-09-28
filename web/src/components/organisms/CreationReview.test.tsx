import { useState } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ResourceForm } from './ResourceForm'
import { useCreationReview } from './CreationReview'
import { Blade } from './Blade'

vi.mock('@/system', () => ({ useConfirm: () => vi.fn() }))

function ButtonEditor() {
  const [open, setOpen] = useState(true)
  const [active, setActive] = useState(false)
  const flow = useCreationReview(open, true)
  return <main><Blade open={open} title="Ubah mode portal" dirty={active} onClose={() => setOpen(false)}
    creation={{ ...flow, prepare: () => {}, summary: <p>Mode portal</p> }}>
    <button aria-pressed={active} onClick={() => setActive(!active)}>Mode voucher</button>
  </Blade></main>
}

function Editor({ save }: { save: (name: string) => Promise<void> }) {
  const [open, setOpen] = useState(true)
  const [name, setName] = useState('')
  const [error, setError] = useState('')
  const flow = useCreationReview(open)
  async function submit() {
    if (!name.trim() || flow.beforeSave()) return
    try { await save(name); setOpen(false) }
    catch { setError('Penyimpanan gagal') }
    finally { flow.finish() }
  }
  return <main>{open ? <ResourceForm title="Buat sumber daya" onClose={() => setOpen(false)} onBack={flow.back} busy={flow.busy}
    review={flow.reviewing ? <p>Nama: {name}</p> : undefined}
    footer={<button form="resource-test" type="submit">Tinjau + buat</button>}
    reviewFooter={<><button disabled={flow.busy} onClick={flow.back}>Sebelumnya</button><button disabled={flow.busy} onClick={() => void submit()}>Buat</button>{error && <p role="alert">{error}</p>}</>}>
    <form id="resource-test" onSubmit={event => { event.preventDefault(); void submit() }}><label>Nama<input required value={name} onChange={event => setName(event.target.value)} /></label></form>
  </ResourceForm> : <p>Sumber daya tersimpan</p>}</main>
}

describe('resource creation review', () => {
  it('protects a button-only draft passed through Blade and preserves it when continuing', async () => {
    const user = userEvent.setup()
    render(<ButtonEditor />)
    await user.click(screen.getByRole('button', { name: 'Mode voucher' }))
    await user.keyboard('{Escape}')
    await screen.findByRole('dialog', { name: 'Buang perubahan?' })
    await user.click(screen.getByRole('button', { name: 'Lanjutkan pengisian' }))
    expect(screen.getByRole('button', { name: 'Mode voucher' }).getAttribute('aria-pressed')).toBe('true')
    await user.click(screen.getByRole('button', { name: 'Tutup' }))
    await user.click(await screen.findByRole('button', { name: 'Buang perubahan' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })
  it('validates before review, preserves Basics, and submits once while navigation is locked', async () => {
    let finish!: () => void
    const save = vi.fn(() => new Promise<void>(resolve => { finish = resolve }))
    const user = userEvent.setup()
    render(<Editor save={save} />)
    await user.click(screen.getByRole('tab', { name: 'Tinjau + buat' }))
    expect(screen.getByRole('textbox', { name: 'Nama' })).toBeTruthy()
    expect(save).not.toHaveBeenCalled()
    await user.type(screen.getByRole('textbox', { name: 'Nama' }), 'Gudang Barat')
    await user.click(screen.getByRole('button', { name: 'Tinjau + buat' }))
    expect(save).not.toHaveBeenCalled()
    expect(screen.queryByRole('textbox', { name: 'Nama' })).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Sebelumnya' }))
    expect(screen.getByRole('textbox', { name: 'Nama' })).toHaveProperty('value', 'Gudang Barat')
    await user.click(screen.getByRole('tab', { name: 'Tinjau + buat' }))
    await user.dblClick(screen.getByRole('button', { name: 'Buat' }))
    expect(save).toHaveBeenCalledExactlyOnceWith('Gudang Barat')
    expect(screen.getByRole('tab', { name: 'Dasar' })).toHaveProperty('disabled', true)
    await user.click(screen.getByRole('button', { name: 'Tutup' }))
    expect(screen.getByRole('dialog')).toBeTruthy()
    finish()
    await screen.findByText('Sumber daya tersimpan')
    expect(screen.getByRole('main')).toBeTruthy()
  })
  it('keeps the review and entered values after a rejected save', async () => {
    const user = userEvent.setup()
    render(<Editor save={vi.fn().mockRejectedValue(new Error('rejected'))} />)
    await user.type(screen.getByRole('textbox', { name: 'Nama' }), 'Gudang Timur')
    await user.click(screen.getByRole('button', { name: 'Tinjau + buat' }))
    await user.click(screen.getByRole('button', { name: 'Buat' }))
    await screen.findByRole('alert')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Sebelumnya' })).toHaveProperty('disabled', false))
    await user.click(screen.getByRole('button', { name: 'Sebelumnya' }))
    expect(screen.getByRole('textbox', { name: 'Nama' })).toHaveProperty('value', 'Gudang Timur')
  })
  it('asks before discarding changes and restores the editor when the user continues', async () => {
    const save = vi.fn()
    const user = userEvent.setup()
    render(<Editor save={save} />)
    await user.type(screen.getByRole('textbox', { name: 'Nama' }), 'Draf gudang')
    await user.keyboard('{Escape}')
    await screen.findByRole('dialog', { name: 'Buang perubahan?' })
    await user.click(screen.getByRole('button', { name: 'Lanjutkan pengisian' }))
    expect(screen.getByRole('textbox', { name: 'Nama' })).toHaveProperty('value', 'Draf gudang')
    await user.click(screen.getByRole('button', { name: 'Tutup' }))
    await user.click(await screen.findByRole('button', { name: 'Buang perubahan' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(screen.getByRole('main')).toBeTruthy()
    expect(save).not.toHaveBeenCalled()
  })

})
