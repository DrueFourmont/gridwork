import { useState } from 'react'
import { Grid } from './Grid'
import { ConflictDialog } from './ConflictDialog'
import { useCellWrite } from '../hooks/useCellWrite'
import { useRows, useSheet } from '../hooks/useRows'
import { useLiveSheet } from '../hooks/useLiveSheet'
import { useAddColumn, useAppendRow } from '../hooks/useSheets'
import type { Cell, ColumnType } from '../api/types'

interface Props {
  sheetId: string
  onBack: () => void
}

const COLUMN_TYPES: ColumnType[] = ['TEXT', 'NUMBER', 'DATE', 'CHECKBOX']

export function SheetView({ sheetId, onBack }: Props) {
  const sheet = useSheet(sheetId)
  const { rows, isPending, isComplete } = useRows(sheetId)
  const { write, conflicts, resolveConflict, error, dismissError, pendingCount } =
    useCellWrite(sheetId)
  const live = useLiveSheet(sheetId)
  const addColumn = useAddColumn(sheetId)
  const appendRow = useAppendRow(sheetId)
  const [columnName, setColumnName] = useState('')
  const [columnType, setColumnType] = useState<ColumnType>('TEXT')
  // What this user typed, kept so the conflict dialog can show both sides.
  const [attempted, setAttempted] = useState<Map<string, string | null>>(new Map())

  const columns = sheet.data?.columns ?? []
  const conflict = conflicts[0]

  return (
    <main className="flex h-screen flex-col">
      <header className="flex shrink-0 items-center gap-3 border-b border-neutral-200 px-4 py-2">
        <button
          type="button"
          data-testid="back-to-sheets"
          onClick={onBack}
          className="text-sm text-blue-600 hover:underline"
        >
          Sheets
        </button>
        <h1 className="text-sm font-semibold">{sheet.data?.name ?? '...'}</h1>
        <span data-testid="row-count" className="text-xs text-neutral-400">
          {rows.length} rows{isComplete ? '' : ' (loading)'}
        </span>
        {pendingCount > 0 && (
          <span data-testid="saving" className="text-xs text-amber-600">
            saving...
          </span>
        )}
        <span
          data-testid="live-status"
          data-status={live.status}
          title={
            live.status === 'live'
              ? 'Connected. Other people\u2019s edits appear here as they happen.'
              : 'Not receiving live updates. Your edits still save.'
          }
          className={[
            'flex items-center gap-1 text-xs',
            live.status === 'live' ? 'text-green-600' : 'text-neutral-400',
          ].join(' ')}
        >
          <span
            className={[
              'inline-block h-2 w-2 rounded-full',
              live.status === 'live'
                ? 'bg-green-500'
                : live.status === 'connecting'
                  ? 'bg-amber-400'
                  : 'bg-neutral-300',
            ].join(' ')}
          />
          {live.status}
        </span>
        <div className="ml-auto flex items-center gap-2">
          <input
            data-testid="new-column-name"
            className="w-32 rounded border border-neutral-300 px-2 py-1 text-xs"
            placeholder="Column name"
            value={columnName}
            onChange={(event) => {
              setColumnName(event.target.value)
            }}
          />
          <select
            data-testid="new-column-type"
            className="rounded border border-neutral-300 px-1 py-1 text-xs"
            value={columnType}
            onChange={(event) => {
              setColumnType(event.target.value as ColumnType)
            }}
          >
            {COLUMN_TYPES.map((type) => (
              <option key={type} value={type}>
                {type}
              </option>
            ))}
          </select>
          <button
            type="button"
            data-testid="add-column"
            disabled={columnName.trim() === '' || addColumn.isPending}
            onClick={() => {
              addColumn.mutate(
                { name: columnName.trim(), type: columnType },
                {
                  onSuccess: () => {
                    setColumnName('')
                  },
                },
              )
            }}
            className="rounded border border-neutral-300 px-2 py-1 text-xs disabled:opacity-40"
          >
            Add column
          </button>
          <button
            type="button"
            data-testid="add-row"
            disabled={appendRow.isPending}
            onClick={() => {
              appendRow.mutate()
            }}
            className="rounded border border-neutral-300 px-2 py-1 text-xs disabled:opacity-40"
          >
            Add row
          </button>
        </div>
      </header>

      {error !== null && (
        <div
          data-testid="write-error"
          className="flex shrink-0 items-center gap-2 bg-red-50 px-4 py-2 text-sm text-red-700"
        >
          {error}
          <button type="button" onClick={dismissError} className="ml-auto text-xs underline">
            Dismiss
          </button>
        </div>
      )}

      {columns.length === 0 && !sheet.isPending && (
        <p className="p-8 text-sm text-neutral-500">
          This sheet has no columns yet. Add one above to start entering data.
        </p>
      )}

      {isPending && <p className="p-8 text-sm text-neutral-500">Loading rows...</p>}

      {columns.length > 0 && (
        <Grid
          rows={rows}
          columns={columns}
          readOnly={false}
          onWrite={(rowId, columnId, value, cell: Cell) => {
            setAttempted((current) => new Map(current).set(`${rowId}::${columnId}`, value))
            write(rowId, columnId, value, cell)
          }}
        />
      )}

      {conflict && (
        <ConflictDialog
          conflict={conflict}
          columnName={columns.find((c) => c.id === conflict.columnId)?.name ?? 'this cell'}
          myValue={attempted.get(`${conflict.rowId}::${conflict.columnId}`) ?? null}
          onResolve={(choice) => {
            resolveConflict(
              conflict,
              choice,
              attempted.get(`${conflict.rowId}::${conflict.columnId}`) ?? null,
            )
          }}
        />
      )}
    </main>
  )
}
