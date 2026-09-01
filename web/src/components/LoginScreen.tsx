import { useState, type FormEvent } from 'react'
import { login, register } from '../api/auth'
import { ApiError } from '../api/client'
import { useAuthStore } from '../state/authStore'

export function LoginScreen() {
  const signIn = useAuthStore((state) => state.signIn)
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      if (mode === 'register') await register(email, password, displayName)
      const issued = await login(email, password)
      signIn(issued.token, email)
    } catch (thrown) {
      // Show the field level message when the API gave one, because "password
      // must be between 12 and 200 characters" is far more useful than
      // "registration failed".
      const message =
        thrown instanceof ApiError
          ? (thrown.fieldErrors[0]?.message ?? thrown.message)
          : 'Could not reach the API.'
      setError(message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-neutral-50">
      <form
        onSubmit={(event) => {
          void submit(event)
        }}
        className="w-full max-w-sm rounded-lg border border-neutral-200 bg-white p-6 shadow-sm"
      >
        <h1 className="text-2xl font-semibold">Gridwork</h1>
        <p className="mt-1 text-sm text-neutral-500">
          {mode === 'login' ? 'Sign in to your sheets.' : 'Create an account.'}
        </p>

        {mode === 'register' && (
          <label className="mt-4 block text-sm">
            Name
            <input
              data-testid="display-name"
              className="mt-1 w-full rounded border border-neutral-300 px-2 py-1.5"
              value={displayName}
              onChange={(event) => {
                setDisplayName(event.target.value)
              }}
              required
            />
          </label>
        )}

        <label className="mt-4 block text-sm">
          Email
          <input
            data-testid="email"
            type="email"
            autoComplete="username"
            className="mt-1 w-full rounded border border-neutral-300 px-2 py-1.5"
            value={email}
            onChange={(event) => {
              setEmail(event.target.value)
            }}
            required
          />
        </label>

        <label className="mt-3 block text-sm">
          Password
          <input
            data-testid="password"
            type="password"
            autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            className="mt-1 w-full rounded border border-neutral-300 px-2 py-1.5"
            value={password}
            onChange={(event) => {
              setPassword(event.target.value)
            }}
            required
          />
        </label>

        {error !== null && (
          <p data-testid="login-error" className="mt-3 text-sm text-red-600">
            {error}
          </p>
        )}

        <button
          type="submit"
          data-testid="submit"
          disabled={busy}
          className="mt-5 w-full rounded bg-blue-600 py-2 text-sm text-white hover:bg-blue-700 disabled:opacity-50"
        >
          {busy ? 'Working...' : mode === 'login' ? 'Sign in' : 'Create account'}
        </button>

        <button
          type="button"
          data-testid="toggle-mode"
          onClick={() => {
            setMode(mode === 'login' ? 'register' : 'login')
            setError(null)
          }}
          className="mt-3 w-full text-center text-sm text-blue-600 hover:underline"
        >
          {mode === 'login' ? 'Need an account?' : 'Already have an account?'}
        </button>
      </form>
    </main>
  )
}
