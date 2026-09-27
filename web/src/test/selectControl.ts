import { fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

/** Select through the control's own listbox; native SelectField remains supported. */
export async function selectControl(control: HTMLElement, change: { target: { value: string } }) {
  if (control.tagName === 'SELECT') { fireEvent.change(control, change); return }
  const user = userEvent.setup()
  if (control.getAttribute('aria-expanded') !== 'true') await user.click(control)
  const option = await waitFor(() => {
    const list = document.getElementById(control.getAttribute('aria-controls') ?? '')
    const candidate = [...(list?.querySelectorAll<HTMLElement>('[role="option"][data-value]') ?? [])]
      .find(element => element.dataset.value === change.target.value)
    if (!candidate || candidate.getAttribute('aria-disabled') === 'true') throw new Error(`Selectable option ${change.target.value} not ready`)
    return candidate
  })
  await user.click(option)
}
