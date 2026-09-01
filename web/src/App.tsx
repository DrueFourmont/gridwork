import { useCallback, useEffect, useState } from 'react'
import { LoginScreen } from './components/LoginScreen'
import { SheetList } from './components/SheetList'
import { SheetView } from './components/SheetView'
import { useAuthStore } from './state/authStore'

/**
 * Three screens, switched by state rather than by a router.
 *
 * Deliberate: react-router would be a dependency and a bundle cost for three
 * views. The price is that a sheet has no shareable URL, which is recorded in
 * docs/HANDOFF.md. When deep links matter, that is the moment to add a router.
 *
 * The open sheet is remembered in sessionStorage so a refresh does not throw
 * you back to the list. That is not a substitute for real URLs, it is the
 * cheapest fix for the most annoying half of not having them.
 */
const OPEN_SHEET_KEY = 'gridwork.openSheet'

function readOpenSheet(): string | null {
  try {
    return sessionStorage.getItem(OPEN_SHEET_KEY)
  } catch {
    return null
  }
}

export default function App() {
  const token = useAuthStore((state) => state.token)
  const [openSheetId, setOpenSheetId] = useState<string | null>(readOpenSheet)

  useEffect(() => {
    try {
      if (openSheetId === null) sessionStorage.removeItem(OPEN_SHEET_KEY)
      else sessionStorage.setItem(OPEN_SHEET_KEY, openSheetId)
    } catch {
      // Storage unavailable. The app still works, it just forgets on refresh.
    }
  }, [openSheetId])

  const closeSheet = useCallback(() => {
    setOpenSheetId(null)
  }, [])

  if (token === null) return <LoginScreen />

  if (openSheetId !== null) {
    return <SheetView sheetId={openSheetId} onBack={closeSheet} />
  }

  return <SheetList onOpen={setOpenSheetId} />
}
