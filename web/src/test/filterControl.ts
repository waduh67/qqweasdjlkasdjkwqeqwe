import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

export async function openFilter(label: string) {
  const user = userEvent.setup()
  const trigger = screen.queryByRole('button', { name: `Filter ${label}` })
  if (trigger) await user.click(trigger)
  else {
    await user.click(await screen.findByRole('button', { name: 'Tambah filter' }))
    await user.click(await screen.findByRole('menuitem', { name: label }))
  }
}
export async function filterControl(label: string) {
  const existing = screen.queryByRole('combobox', { name: label })
  if (existing) return existing
  await openFilter(label)
  return screen.findByRole('combobox', { name: label })
}
