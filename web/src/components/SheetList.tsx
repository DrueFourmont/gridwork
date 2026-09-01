import { useState } from 'react'
import { useCreateSheet, useSheetList } from '../hooks/useSheets'
import { useAuthStore } from '../state/authStore'

interface Props {
  onOpen: (sheetId: string) => void
}

export function SheetList({ onOpen }: Props) {
  const { data, isPending, isError } = useSheetList()
  const createSheet = useCreateSheet()
  const signOut = useAuthStore((state) => state.signOut)
  const [name, setName] = useState('')

  return (
    <main className="mx-auto max-w-2xl p-8">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Your sheets</h1>
        <button
          type="button"
          data-testid="sign-out"
          onClick={signOut}
          className="text-sm text-neutral-500 hover:underline"
        >
          Sign out
        </button>
      </div>

      <form
        className="mt-6 flex gap-2"
        onSubmit={(event) => {
          event.preventDefault()
          if (name.trim() === '') return
          createSheet.mutate(name.trim(), {
            onSuccess: () => {
              setName('')
            },
          })
        }}
      >
        <input
          data-testid="new-sheet-name"
          className="flex-1 rounded border border-neutral-300 px-2 py-1.5 text-sm"
          placeholder="New sheet name"
          value={name}
          onChange={(event) => {
            setName(event.target.value)
          }}
        />
        <button
          type="submit"
          data-testid="create-sheet"
          disabled={createSheet.isPending}
          className="rounded bg-blue-600 px-3 py-1.5 text-sm text-white disabled:opacity-50"
        >
          Create
        </button>
      </form>

      {isPending && <p className="mt-6 text-sm text-neutral-500">Loading...</p>}
      {isError && <p className="mt-6 text-sm text-red-600">Could not load your sheets.</p>}

      <ul className="mt-6 divide-y divide-neutral-200 rounded border border-neutral-200">
        {data?.items.map((sheet) => (
          <li key={sheet.id}>
            <button
              type="button"
              data-testid={`open-sheet-${sheet.name}`}
              onClick={() => {
                onOpen(sheet.id)
              }}
              className="w-full px-4 py-3 text-left text-sm hover:bg-neutral-50"
            >
              {sheet.name}
            </button>
          </li>
        ))}
        {data?.items.length === 0 && (
          <li className="px-4 py-6 text-center text-sm text-neutral-400">
            No sheets yet. Create one above.
          </li>
        )}
      </ul>
    </main>
  )
}
