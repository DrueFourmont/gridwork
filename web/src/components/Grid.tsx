import { useCallback, useRef, useState } from 'react'
import { useVirtualizer } from '@tanstack/react-virtual'
import { GridCell } from './GridCell'
import type { Cell, Column, Row } from '../api/types'

const ROW_HEIGHT = 32
const COLUMN_WIDTH = 180

interface Props {
  rows: Row[]
  columns: Column[]
  onWrite: (rowId: string, columnId: string, value: string | null, cell: Cell) => void
  readOnly: boolean
}

interface Selection {
  rowId: string
  columnId: string
}

/**
 * The virtualised grid.
 *
 * A 2,000 row sheet with 5 columns is 10,000 cells. Rendering all of them
 * costs about ten thousand DOM nodes, and the browser then does layout and
 * paint on every one of them for every scroll frame. It will not hold 60 fps,
 * and no amount of memoisation fixes it, because the work is in the DOM rather
 * than in React.
 *
 * So only the visible rows exist. The scroll container is given the full
 * height, about 64,000 pixels, by a single spacer div. Roughly thirty rows are
 * rendered and shifted into place with a transform as you scroll. The
 * scrollbar behaves exactly as if all 2,000 rows were there, because as far as
 * the browser is concerned the content really is that tall.
 *
 * Rows are virtualised; columns are not. A sheet is capped at 100 columns and
 * a handful are on screen, so the win is small and the added complexity of two
 * dimensions is not.
 */
export function Grid({ rows, columns, onWrite, readOnly }: Props) {
  const scrollRef = useRef<HTMLDivElement>(null)
  const [selection, setSelection] = useState<Selection | null>(null)
  const [editing, setEditing] = useState<Selection | null>(null)

  // The lint rule react-hooks/incompatible-library warns here, and it is
  // right: React Compiler cannot auto memoise a component using
  // useVirtualizer, because the hook returns fresh function identities every
  // render. That is precisely why GridCell is wrapped in memo() by hand. The
  // warning is left in place rather than silenced, because it is true.
  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => ROW_HEIGHT,
    // Render a few rows above and below the viewport so a fast scroll shows
    // content rather than blank space while React catches up.
    overscan: 8,
  })

  const commit = useCallback(
    (row: Row, column: Column, cell: Cell, value: string | null) => {
      setEditing(null)
      if (!readOnly) onWrite(row.id, column.id, value, cell)
    },
    [onWrite, readOnly],
  )

  const items = virtualizer.getVirtualItems()

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex shrink-0 border-b-2 border-neutral-300 bg-neutral-50">
        <div className="w-12 shrink-0 border-r border-neutral-200 px-2 py-1 text-xs text-neutral-400">
          #
        </div>
        {columns.map((column) => (
          <div
            key={column.id}
            style={{ width: COLUMN_WIDTH }}
            className="shrink-0 border-r border-neutral-200 px-2 py-1 text-sm font-medium"
          >
            {column.name}
            <span className="ml-1 text-xs font-normal text-neutral-400">{column.type}</span>
          </div>
        ))}
      </div>

      <div
        ref={scrollRef}
        data-testid="grid-scroll"
        className="min-h-0 flex-1 overflow-auto"
        role="grid"
        aria-rowcount={rows.length}
      >
        {/* One spacer as tall as every row combined, so the scrollbar is honest. */}
        <div style={{ height: virtualizer.getTotalSize(), position: 'relative', width: '100%' }}>
          {items.map((item) => {
            const row = rows[item.index]
            if (!row) return null
            return (
              <div
                key={row.id}
                data-testid={`row-${String(item.index)}`}
                role="row"
                className="absolute top-0 left-0 flex"
                style={{
                  height: ROW_HEIGHT,
                  // translateY rather than `top`, because a transform is
                  // composited and does not force the browser to redo layout
                  // for every row on every frame.
                  transform: `translateY(${String(item.start)}px)`,
                }}
              >
                <div className="w-12 shrink-0 border-r border-b border-neutral-200 bg-neutral-50 px-2 text-xs leading-8 text-neutral-400">
                  {item.index + 1}
                </div>
                {columns.map((column) => {
                  const cell = row.cells.find((candidate) => candidate.columnId === column.id) ?? {
                    columnId: column.id,
                    value: null,
                    version: 1,
                  }
                  const isSelected = selection?.rowId === row.id && selection.columnId === column.id
                  const isEditing = editing?.rowId === row.id && editing.columnId === column.id
                  return (
                    <div key={column.id} style={{ width: COLUMN_WIDTH }} className="shrink-0">
                      <GridCell
                        cell={cell}
                        column={column}
                        rowId={row.id}
                        isSelected={isSelected}
                        isEditing={isEditing}
                        onSelect={() => {
                          setSelection({ rowId: row.id, columnId: column.id })
                        }}
                        onStartEdit={() => {
                          if (!readOnly) setEditing({ rowId: row.id, columnId: column.id })
                        }}
                        onCommit={(value) => {
                          commit(row, column, cell, value)
                        }}
                        onCancel={() => {
                          setEditing(null)
                        }}
                      />
                    </div>
                  )
                })}
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}
