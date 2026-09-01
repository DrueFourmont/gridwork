import type { CellConflict } from '../api/types'

interface Props {
  conflict: CellConflict
  columnName: string
  /** What this user typed, which the server rejected. */
  myValue: string | null
  onResolve: (choice: 'mine' | 'theirs') => void
}

/**
 * What a 409 looks like to a person.
 *
 * The API hands back the other person's value inside the conflict, so this can
 * show both sides without another request. That is the entire reason
 * CellConflictException carries `actualValue`, and it is the difference
 * between a merge prompt appearing instantly and appearing after a spinner.
 *
 * No "merge" button, and no attempt to combine the two values. For a single
 * cell there is no sensible automatic merge, and inventing one silently loses
 * somebody's work. A person picks.
 */
export function ConflictDialog({ conflict, columnName, myValue, onResolve }: Props) {
  return (
    <div
      role="alertdialog"
      aria-labelledby="conflict-title"
      data-testid="conflict-dialog"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4"
    >
      <div className="w-full max-w-md rounded-lg bg-white p-5 shadow-xl">
        <h2 id="conflict-title" className="text-base font-semibold">
          Someone else edited this cell
        </h2>
        <p className="mt-1 text-sm text-neutral-500">
          Your change to <span className="font-medium">{columnName}</span> was not saved, because
          the cell changed while you were editing it. Nothing was overwritten.
        </p>

        <div className="mt-4 space-y-2">
          <div className="rounded border border-neutral-200 p-3">
            <div className="text-xs tracking-wide text-neutral-400 uppercase">Their value</div>
            <div data-testid="conflict-theirs" className="mt-1 text-sm">
              {conflict.actualValue ?? <span className="text-neutral-400">(empty)</span>}
            </div>
          </div>
          <div className="rounded border border-blue-200 bg-blue-50 p-3">
            <div className="text-xs tracking-wide text-blue-500 uppercase">Your value</div>
            <div data-testid="conflict-mine" className="mt-1 text-sm">
              {myValue ?? <span className="text-neutral-400">(empty)</span>}
            </div>
          </div>
        </div>

        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            data-testid="conflict-keep-theirs"
            onClick={() => {
              onResolve('theirs')
            }}
            className="rounded border border-neutral-300 px-3 py-1.5 text-sm hover:bg-neutral-50"
          >
            Keep theirs
          </button>
          <button
            type="button"
            data-testid="conflict-keep-mine"
            onClick={() => {
              onResolve('mine')
            }}
            className="rounded bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700"
          >
            Keep mine
          </button>
        </div>

        <p className="mt-3 text-xs text-neutral-400">
          Keeping yours writes again, this time expecting version {conflict.actualVersion}.
        </p>
      </div>
    </div>
  )
}
