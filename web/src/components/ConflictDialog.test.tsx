import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ConflictDialog } from './ConflictDialog'

const conflict = {
  rowId: 'row-1',
  columnId: 'col-1',
  expectedVersion: 1,
  actualVersion: 4,
  actualValue: 'their text',
}

describe('ConflictDialog', () => {
  it('shows both values so a person can actually choose', () => {
    render(
      <ConflictDialog
        conflict={conflict}
        columnName="Task"
        myValue="my text"
        onResolve={vi.fn()}
      />,
    )
    expect(screen.getByTestId('conflict-theirs')).toHaveTextContent('their text')
    expect(screen.getByTestId('conflict-mine')).toHaveTextContent('my text')
  })

  it('says plainly that nothing was overwritten', () => {
    // The single most reassuring fact after a failed save, and the one a user
    // most needs to know. It is a promise the API keeps, so the UI should say it.
    render(<ConflictDialog conflict={conflict} columnName="Task" myValue="x" onResolve={vi.fn()} />)
    expect(screen.getByText(/Nothing was overwritten/i)).toBeInTheDocument()
  })

  it('names the column, because a bare cell reference means nothing to a person', () => {
    render(
      <ConflictDialog conflict={conflict} columnName="Due date" myValue="x" onResolve={vi.fn()} />,
    )
    expect(screen.getByText('Due date')).toBeInTheDocument()
  })

  it('reports keep mine', async () => {
    const onResolve = vi.fn()
    const user = userEvent.setup()
    render(
      <ConflictDialog conflict={conflict} columnName="Task" myValue="x" onResolve={onResolve} />,
    )

    await user.click(screen.getByTestId('conflict-keep-mine'))

    expect(onResolve).toHaveBeenCalledWith('mine')
  })

  it('reports keep theirs', async () => {
    const onResolve = vi.fn()
    const user = userEvent.setup()
    render(
      <ConflictDialog conflict={conflict} columnName="Task" myValue="x" onResolve={onResolve} />,
    )

    await user.click(screen.getByTestId('conflict-keep-theirs'))

    expect(onResolve).toHaveBeenCalledWith('theirs')
  })

  it('shows an empty other value as empty rather than as blank space', () => {
    render(
      <ConflictDialog
        conflict={{ ...conflict, actualValue: null }}
        columnName="Task"
        myValue="mine"
        onResolve={vi.fn()}
      />,
    )
    expect(screen.getByTestId('conflict-theirs')).toHaveTextContent('(empty)')
  })

  it('is announced as an alert dialog', () => {
    render(<ConflictDialog conflict={conflict} columnName="Task" myValue="x" onResolve={vi.fn()} />)
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
  })
})
