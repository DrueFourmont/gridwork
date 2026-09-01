import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { Grid } from './Grid'
import type { Column, Row } from '../api/types'

const columns: Column[] = [
  { id: 'col-a', name: 'Task', type: 'TEXT', position: 0, version: 1 },
  { id: 'col-b', name: 'Amount', type: 'NUMBER', position: 1, version: 1 },
]

function makeRows(count: number): Row[] {
  return Array.from({ length: count }, (_, index) => ({
    id: `row-${String(index)}`,
    position: index,
    version: 1,
    cells: columns.map((column) => ({
      columnId: column.id,
      value: `${column.name}-${String(index)}`,
      version: 1,
    })),
  }))
}

describe('Grid', () => {
  it('renders only a window of rows, not all of them', () => {
    // The load bearing assertion for the 60 fps budget in CLAUDE.md. Ten
    // thousand cells in the DOM cannot hold 60 fps no matter how well the
    // React side is memoised, because the cost is layout and paint. This test
    // proves the DOM stays small without needing a frame counter.
    render(<Grid rows={makeRows(2000)} columns={columns} onWrite={vi.fn()} readOnly={false} />)

    const rendered = screen.getAllByRole('row')
    expect(rendered.length).toBeLessThan(60)
    expect(rendered.length).toBeGreaterThan(0)
  })

  it('tells assistive technology how many rows there really are', () => {
    // Virtualising the DOM must not lie to a screen reader about the size of
    // the sheet, which is what aria-rowcount is for.
    render(<Grid rows={makeRows(2000)} columns={columns} onWrite={vi.fn()} readOnly={false} />)
    expect(screen.getByRole('grid')).toHaveAttribute('aria-rowcount', '2000')
  })

  it('shows the cell values it does render', () => {
    render(<Grid rows={makeRows(5)} columns={columns} onWrite={vi.fn()} readOnly={false} />)
    expect(screen.getByTestId('cell-row-0-col-a')).toHaveTextContent('Task-0')
  })

  it('double clicking a cell opens an editor, and Enter commits the new value', async () => {
    const user = userEvent.setup()
    const onWrite = vi.fn()
    render(<Grid rows={makeRows(3)} columns={columns} onWrite={onWrite} readOnly={false} />)

    await user.dblClick(screen.getByTestId('cell-row-1-col-a'))
    const input = screen.getByTestId('cell-input-row-1-col-a')
    await user.clear(input)
    await user.type(input, 'edited{Enter}')

    expect(onWrite).toHaveBeenCalledWith('row-1', 'col-a', 'edited', expect.anything())
  })

  it('Escape abandons an edit without writing anything', async () => {
    const user = userEvent.setup()
    const onWrite = vi.fn()
    render(<Grid rows={makeRows(3)} columns={columns} onWrite={onWrite} readOnly={false} />)

    await user.dblClick(screen.getByTestId('cell-row-1-col-a'))
    await user.type(screen.getByTestId('cell-input-row-1-col-a'), 'discard me{Escape}')

    expect(onWrite).not.toHaveBeenCalled()
  })

  it('clearing a cell writes null rather than an empty string', async () => {
    // The API treats null and "" as the same empty, and the domain stores
    // empty as null. Sending "" would work but would make the client and the
    // stored value disagree about what blank looks like.
    const user = userEvent.setup()
    const onWrite = vi.fn()
    render(<Grid rows={makeRows(3)} columns={columns} onWrite={onWrite} readOnly={false} />)

    await user.dblClick(screen.getByTestId('cell-row-0-col-a'))
    const input = screen.getByTestId('cell-input-row-0-col-a')
    await user.clear(input)
    await user.type(input, '{Enter}')

    expect(onWrite).toHaveBeenCalledWith('row-0', 'col-a', null, expect.anything())
  })

  it('a read only sheet cannot be edited', async () => {
    // Viewers get a 403 from the API anyway, but letting them type and then
    // rejecting it is a worse experience than not letting them start.
    const user = userEvent.setup()
    const onWrite = vi.fn()
    render(<Grid rows={makeRows(3)} columns={columns} onWrite={onWrite} readOnly />)

    await user.dblClick(screen.getByTestId('cell-row-0-col-a'))

    expect(screen.queryByTestId('cell-input-row-0-col-a')).not.toBeInTheDocument()
  })

  it('carries the cell version in the DOM so a test can see optimistic updates land', () => {
    render(<Grid rows={makeRows(2)} columns={columns} onWrite={vi.fn()} readOnly={false} />)
    expect(screen.getByTestId('cell-row-0-col-a')).toHaveAttribute('data-version', '1')
  })
})
