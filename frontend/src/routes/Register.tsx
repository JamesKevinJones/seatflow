import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'
import { Button, Eyebrow, Field, Notice } from '../components/ui'

export function Register() {
  const { register } = useAuth()
  const navigate = useNavigate()

  const [fullName, setFullName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<ApiError | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      await register(email, password, fullName)
      navigate('/', { replace: true })
    } catch (caught) {
      setError(caught instanceof ApiError ? caught : new ApiError(0, null))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="mx-auto w-full max-w-sm px-5 py-16 sm:py-24">
      <Eyebrow>Account</Eyebrow>
      <h1 className="mt-2 text-[34px] leading-[1.05]">Create account</h1>
      <p className="mt-3 text-[15px] text-[var(--color-ink-muted)]">
        Takes a moment. You'll be signed in straight away.
      </p>

      <form onSubmit={onSubmit} className="mt-8 flex flex-col gap-4" noValidate>
        {/* Field-level messages come from the server's problem+json; this
            catches the ones that are not about a single field. */}
        {error && error.fieldErrors.length === 0 ? <Notice>{error.detail}</Notice> : null}

        <Field
          label="Full name"
          autoComplete="name"
          required
          value={fullName}
          onChange={(e) => setFullName(e.target.value)}
          error={error?.fieldMessage('fullName')}
        />
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
          autoComplete="new-password"
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          hint="At least 12 characters. Length beats symbols."
          error={error?.fieldMessage('password')}
        />

        <Button type="submit" disabled={submitting} className="mt-1 w-full">
          {submitting ? 'Creating…' : 'Create account'}
        </Button>
      </form>

      <p className="mt-6 text-[14px] text-[var(--color-ink-muted)]">
        Already have one?{' '}
        <Link to="/sign-in" className="text-[var(--color-brass-deep)] underline underline-offset-2">
          Sign in
        </Link>
      </p>
    </main>
  )
}
