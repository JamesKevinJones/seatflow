import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode } from 'react'
import { useId } from 'react'

/* Small shared primitives. Deliberately few - a component library would be
   more code than the five screens that use it. */

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'quiet' | 'ghost'
  tone?: 'paper' | 'house'
}

export function Button({
  variant = 'primary',
  tone = 'paper',
  className = '',
  children,
  ...rest
}: ButtonProps) {
  const base =
    'inline-flex items-center justify-center gap-2 rounded-[var(--radius-control)] ' +
    'px-4 py-2.5 text-sm font-medium transition-[background-color,border-color,color,opacity] ' +
    'duration-150 ease-[var(--ease-snap)] disabled:opacity-45 disabled:pointer-events-none ' +
    'active:translate-y-px'

  const variants: Record<string, string> = {
    // Solid brass, no gradient. Hover darkens rather than lifts.
    primary:
      'bg-[var(--color-brass)] text-[#1a1206] hover:bg-[var(--color-brass-bright)] ' +
      'shadow-[var(--shadow-raise)]',
    quiet:
      tone === 'house'
        ? 'border border-[var(--color-house-rule)] text-[var(--color-house-text)] hover:bg-white/5'
        : 'border border-[var(--color-rule)] bg-[var(--color-paper-raised)] hover:bg-[var(--color-paper-sunk)]',
    ghost:
      tone === 'house'
        ? 'text-[var(--color-house-muted)] hover:text-[var(--color-house-text)]'
        : 'text-[var(--color-ink-muted)] hover:text-[var(--color-ink)]',
  }

  return (
    <button className={`${base} ${variants[variant]} ${className}`} {...rest}>
      {children}
    </button>
  )
}

type FieldProps = InputHTMLAttributes<HTMLInputElement> & {
  label: string
  hint?: string
  error?: string
}

export function Field({ label, hint, error, className = '', ...rest }: FieldProps) {
  const id = useId()
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined

  return (
    <div className="flex flex-col gap-1.5">
      <label
        htmlFor={id}
        className="font-mono text-[11px] uppercase tracking-[0.12em] text-[var(--color-ink-muted)]"
      >
        {label}
      </label>
      <input
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={`rounded-[var(--radius-control)] border bg-[var(--color-paper-raised)] px-3 py-2.5 text-[15px]
          transition-colors duration-150 placeholder:text-[var(--color-ink-faint)]
          ${error ? 'border-[var(--color-alarm)]' : 'border-[var(--color-rule)]'} ${className}`}
        {...rest}
      />
      {error ? (
        <p id={`${id}-error`} className="text-[13px] text-[var(--color-alarm)]">
          {error}
        </p>
      ) : hint ? (
        <p id={`${id}-hint`} className="text-[13px] text-[var(--color-ink-faint)]">
          {hint}
        </p>
      ) : null}
    </div>
  )
}

/** A short uppercase mono label. Used for metadata, never for prose. */
export function Eyebrow({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <span
      className={`font-mono text-[11px] uppercase tracking-[0.14em] text-[var(--color-ink-faint)] ${className}`}
    >
      {children}
    </span>
  )
}

/** Form-level failure. States what happened and what to do, without apologising. */
export function Notice({ children }: { children: ReactNode }) {
  return (
    <div
      role="alert"
      className="rounded-[var(--radius-control)] border border-[var(--color-alarm)]/30
        bg-[var(--color-alarm-soft)] px-3.5 py-3 text-[14px] text-[var(--color-alarm)]"
    >
      {children}
    </div>
  )
}

/**
 * Skeleton, not a spinner. A spinner on a fast local call is a flash; a
 * skeleton holds the shape the content will occupy.
 */
export function Skeleton({ className = '' }: { className?: string }) {
  return (
    <div
      className={`animate-pulse rounded-[var(--radius-control)] bg-[var(--color-paper-sunk)] ${className}`}
    />
  )
}
