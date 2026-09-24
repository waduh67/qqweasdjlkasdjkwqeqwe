import { useState } from 'react'
import { expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MultiCombobox } from './MultiCombobox'

const technicians = [{ id: 'tech-one', name: 'Budi Teknisi' }, { id: 'tech-two', name: 'Sari Teknisi' }]
const load = async () => technicians
const toId = (row: typeof technicians[number]) => row.id
const toLabel = (row: typeof technicians[number]) => row.name

it('keeps portal menu pointer clicks inside the picker and persists both technician selections', async () => {
  const changed = vi.fn()
  function Form() {
    const [values, setValues] = useState<string[]>([])
    return <><MultiCombobox values={values} onChange={next => { setValues(next); changed(next) }} fetchOptions={load} toId={toId} toLabel={toLabel} debounceMs={0} placeholder="Pilih teknisi" />
      <button type="button">Catatan pekerjaan</button><output aria-label="Penugasan">{values.join(',')}</output></>
  }
  const user = userEvent.setup()
  render(<Form />)
  await user.click(screen.getByRole('combobox', { name: 'Pilih teknisi' }))
  await user.click(await screen.findByRole('menuitemcheckbox', { name: 'Budi Teknisi' }))
  expect(changed).toHaveBeenLastCalledWith(['tech-one'])
  await user.click(await screen.findByRole('menuitemcheckbox', { name: 'Sari Teknisi' }))
  expect(changed).toHaveBeenLastCalledWith(['tech-one', 'tech-two'])
  await user.click(screen.getByRole('button', { name: 'Catatan pekerjaan' }))
  expect(screen.getByRole('status', { name: 'Penugasan' }).textContent).toBe('tech-one,tech-two')
  expect(screen.queryByRole('menu')).toBeNull()
})
