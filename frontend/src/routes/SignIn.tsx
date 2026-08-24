import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'
import { Button, Eyebrow, Field, Notice } from '../components/ui'

export function SignIn() {
  const { signIn } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const from = (location.state as { from?: Location })?.from?.pathname ?? '/'

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<ApiError | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      await signIn(email, password)
      navigate(from, { replace: true })
    } catch (caught) {
      setError(caught instanceof ApiError ? caught : new ApiError(0, null))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="mx-auto w-full max-w-sm px-5 py-16 sm:py-24">
      <Eyebrow>Account</Eyebrow>
      <h1 className="mt-2 text-[34px] leading-[1.05]">Sign in</h1>
      <p className="mt-3 text-[15px] text-[var(--color-ink-muted)]">
        You need an account to hold seats. Browsing is open to everyone.
      </p>

      <form onSubmit={onSubmit} className="mt-8 flex flex-col gap-4" noValidate>
        {error ? <Notice>{error.detail}</Notice> : null}

        <Field
          label="Email"
          type="email"
          autoComplete="email"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          error={error?.fieldMessage('email')}
        />
        <Field
          label="Password"
          type="password"
          autoComplete="current-password"
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          error={error?.fieldMessage('password')}
        />

        <Button type="submit" disabled={submitting} className="mt-1 w-full">
          {submitting ? 'Signing in…' : 'Sign in'}
        </Button>
      </form>

      <p className="mt-6 text-[14px] text-[var(--color-ink-muted)]">
        No account?{' '}
        <Link to="/register" className="text-[var(--color-brass-deep)] underline underline-offset-2">
          Create one
        </Link>
      </p>
    </main>
  )
}
