import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

/** Exercise the visible menu before invoking a row command. */
export async function clickRowAction(name: string) {
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Aksi baris' }))
  await user.click(await screen.findByRole('menuitem', { name }))
}
