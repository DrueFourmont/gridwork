import { create } from 'zustand'
import { setTokenReader, setUnauthorizedHandler } from '../api/client'

/**
 * Where the bearer token lives.
 *
 * sessionStorage, chosen deliberately over the alternatives:
 *
 *   in memory      lost on every refresh, which makes the app unusable to demo
 *   localStorage   survives a browser restart, so a stolen token lives longest
 *   sessionStorage survives a refresh, dies with the tab
 *
 * All three are readable by any script running on the page, so none of them
 * defends against XSS. The genuinely correct answer is an httpOnly cookie the
 * JavaScript cannot read, which needs CSRF protection and a server change, and
 * is not in this phase. sessionStorage plus a 15 minute token expiry keeps the
 * exposure window small. Recorded in docs/HANDOFF.md as a known issue.
 */
const STORAGE_KEY = 'gridwork.token'

function readStoredToken(): string | null {
  try {
    return sessionStorage.getItem(STORAGE_KEY)
  } catch {
    // Private browsing and some hardened configurations throw on access
    // rather than returning null. A storage failure should log you out, not
    // crash the app.
    return null
  }
}

function writeStoredToken(token: string | null): void {
  try {
    if (token === null) sessionStorage.removeItem(STORAGE_KEY)
    else sessionStorage.setItem(STORAGE_KEY, token)
  } catch {
    // Nothing to do. The token stays in memory for this page load.
  }
}

interface AuthState {
  token: string | null
  email: string | null
  signIn: (token: string, email: string) => void
  signOut: () => void
}

export const useAuthStore = create<AuthState>((set) => ({
  token: readStoredToken(),
  email: null,
  signIn: (token, email) => {
    writeStoredToken(token)
    set({ token, email })
  },
  signOut: () => {
    writeStoredToken(null)
    set({ token: null, email: null })
  },
}))

// The api client reads the token through a function, so it always sees the
// current one rather than whatever was there when a module was first imported.
setTokenReader(() => useAuthStore.getState().token)

// Any 401 from any call drops the session, so an expired token sends you back
// to the login screen instead of leaving the grid in a broken half state.
setUnauthorizedHandler(() => {
  useAuthStore.getState().signOut()
})
