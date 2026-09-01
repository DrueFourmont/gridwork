import { memo, useEffect, useRef, useState } from 'react'
import type { Cell, Column } from '../api/types'

interface Props {
  cell: Cell
  column: Column
  rowId: string
  isEditing: boolean
  isSelected: boolean
  onSelect: () => void
  onStartEdit: () => void
  onCommit: (value: string | null) => void
  onCancel: () => void
}

/**
 * One cell. Two modes: a div showing the value, or an input being edited.
 *
 * memo() is not decoration here. During a scroll the grid re-renders on every
 * frame, and without it every visible cell re-renders with it. That is the
 * difference between hitting the 60 fps budget in CLAUDE.md and missing it.
 */
export const GridCell = memo(function GridCell({
  cell,
  column,
  rowId,
  isEditing,
  isSelected,
  onSelect,
  onStartEdit,
  onCommit,
  onCancel,
}: Props) {
  const [draft, setDraft] = useState(cell.value ?? '')
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (isEditing) {
      setDraft(cell.value ?? '')
      inputRef.current?.focus()
      inputRef.current?.select()
    }
  }, [isEditing, cell.value])

  if (isEditing) {
    return (
      <input
        ref={inputRef}
        data-testid={`cell-input-${rowId}-${column.id}`}
        className="h-full w-full border-2 border-blue-500 px-2 text-sm outline-none"
        value={draft}
        onChange={(event) => {
          setDraft(event.target.value)
        }}
        onBlur={() => {
          onCommit(draft === '' ? null : draft)
        }}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault()
            onCommit(draft === '' ? null : draft)
          }
          if (event.key === 'Escape') {
            event.preventDefault()
            onCancel()
          }
        }}
      />
    )
  }

  return (
    <div
      data-testid={`cell-${rowId}-${column.id}`}
      data-version={cell.version}
      role="gridcell"
      tabIndex={isSelected ? 0 : -1}
      onClick={onSelect}
      onDoubleClick={onStartEdit}
      onKeyDown={(event) => {
        // Enter to edit matches every spreadsheet anyone has used. Typing a
        // printable character starting an edit is Phase 2 scope creep, noted.
        if (event.key === 'Enter') {
          event.preventDefault()
          onStartEdit()
        }
      }}
      className={[
        'h-full w-full cursor-cell truncate border-r border-b border-neutral-200 px-2 text-sm leading-8',
        isSelected ? 'bg-blue-50 ring-2 ring-inset ring-blue-500' : 'bg-white',
      ].join(' ')}
    >
      {cell.value ?? ''}
    </div>
  )
})
