import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode, SelectHTMLAttributes } from 'react'

/**
 * The design system: the handful of pieces every page is built from.
 *
 * One file rather than one file per component, because each of these is a dozen lines and they are always read
 * together — splitting them would mean eleven imports in every page. Tailwind classes are collected here so a
 * page never spells out a colour: changing the shade of "danger" is one edit in this file.
 */

type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger'

const toneRing: Record<Tone, string> = {
  neutral: 'bg-slate-100 text-slate-700 ring-slate-200',
  info: 'bg-sky-50 text-sky-800 ring-sky-200',
  success: 'bg-emerald-50 text-emerald-800 ring-emerald-200',
  warning: 'bg-amber-50 text-amber-900 ring-amber-200',
  danger: 'bg-red-50 text-red-800 ring-red-200',
}

export function Badge({ tone = 'neutral', children }: { tone?: Tone; children: ReactNode }) {
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset whitespace-nowrap ${toneRing[tone]}`}
    >
      {children}
    </span>
  )
}

type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'ghost'

const buttonVariant: Record<ButtonVariant, string> = {
  primary: 'bg-slate-900 text-white hover:bg-slate-700 focus-visible:outline-slate-900',
  secondary: 'bg-white text-slate-900 ring-1 ring-slate-300 hover:bg-slate-50 focus-visible:outline-slate-400',
  danger: 'bg-red-600 text-white hover:bg-red-500 focus-visible:outline-red-600',
  ghost: 'text-slate-700 hover:bg-slate-100 focus-visible:outline-slate-400',
}

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  loading?: boolean
}

export function Button({ variant = 'primary', loading = false, children, className = '', ...rest }: ButtonProps) {
  return (
    <button
      {...rest}
      // Disabled while loading, so a double click cannot send a second assignment or a second order.
      disabled={rest.disabled || loading}
      aria-busy={loading || undefined}
      className={`inline-flex items-center justify-center gap-2 rounded-md px-3 py-2 text-sm font-medium transition focus-visible:outline-2 focus-visible:outline-offset-2 disabled:cursor-not-allowed disabled:opacity-50 ${buttonVariant[variant]} ${className}`}
    >
      {loading && <Spinner size="sm" />}
      {children}
    </button>
  )
}

export function Spinner({ size = 'md' }: { size?: 'sm' | 'md' }) {
  const pixels = size === 'sm' ? 'h-3.5 w-3.5' : 'h-5 w-5'
  return (
    <span
      role="status"
      aria-label="Loading"
      className={`inline-block animate-spin rounded-full border-2 border-current border-t-transparent ${pixels}`}
    />
  )
}

export function Card({ title, actions, children }: { title?: ReactNode; actions?: ReactNode; children: ReactNode }) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white shadow-sm">
      {(title || actions) && (
        <header className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 px-4 py-3">
          {typeof title === 'string' ? <h2 className="text-sm font-semibold text-slate-900">{title}</h2> : title}
          {actions}
        </header>
      )}
      <div className="p-4">{children}</div>
    </section>
  )
}

export function Stat({ label, value, hint }: { label: string; value: ReactNode; hint?: string }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <dt className="text-xs font-medium tracking-wide text-slate-500 uppercase">{label}</dt>
      <dd className="mt-1 text-2xl font-semibold text-slate-900">{value}</dd>
      {hint && <p className="mt-1 text-xs text-slate-500">{hint}</p>}
    </div>
  )
}

export function PageHeader({ title, description, actions }: { title: string; description?: string; actions?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 className="text-xl font-semibold text-slate-900">{title}</h1>
        {description && <p className="mt-1 max-w-2xl text-sm text-slate-600">{description}</p>}
      </div>
      {actions}
    </div>
  )
}

export function Field({
  label,
  htmlFor,
  error,
  hint,
  children,
}: {
  label: string
  htmlFor: string
  error?: string
  hint?: string
  children: ReactNode
}) {
  return (
    <div>
      <label htmlFor={htmlFor} className="block text-sm font-medium text-slate-800">
        {label}
      </label>
      <div className="mt-1">{children}</div>
      {/* The server's field errors land here, so the message a user reads is the rule the server enforced. */}
      {error ? (
        <p id={`${htmlFor}-error`} role="alert" className="mt-1 text-xs text-red-700">
          {error}
        </p>
      ) : (
        hint && <p className="mt-1 text-xs text-slate-500">{hint}</p>
      )}
    </div>
  )
}

const inputClasses =
  'block w-full rounded-md border-0 bg-white px-3 py-2 text-sm text-slate-900 ring-1 ring-slate-300 ring-inset placeholder:text-slate-400 focus:ring-2 focus:ring-slate-700 disabled:bg-slate-50'

export function Input({ className = '', ...rest }: InputHTMLAttributes<HTMLInputElement>) {
  return <input {...rest} className={`${inputClasses} ${className}`} />
}

export function Select({ className = '', children, ...rest }: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select {...rest} className={`${inputClasses} ${className}`}>
      {children}
    </select>
  )
}

export function Table({ head, children }: { head: ReactNode[]; children: ReactNode }) {
  return (
    <div className="overflow-x-auto">
      <table className="min-w-full divide-y divide-slate-200 text-sm">
        <thead>
          <tr>
            {head.map((cell, index) => (
              <th key={index} scope="col" className="px-3 py-2 text-left text-xs font-semibold tracking-wide text-slate-500 uppercase">
                {cell}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">{children}</tbody>
      </table>
    </div>
  )
}

export function Cell({ children, className = '' }: { children?: ReactNode; className?: string }) {
  return <td className={`px-3 py-2 align-middle text-slate-700 ${className}`}>{children}</td>
}

/** The three states every list has to handle, written once so no page forgets one of them. */
export function Loading({ label = 'Loading' }: { label?: string }) {
  return (
    <div className="flex items-center gap-2 py-8 text-sm text-slate-500">
      <Spinner />
      {label}…
    </div>
  )
}

export function EmptyState({ title, hint }: { title: string; hint?: string }) {
  return (
    <div className="rounded-md border border-dashed border-slate-300 px-4 py-8 text-center">
      <p className="text-sm font-medium text-slate-700">{title}</p>
      {hint && <p className="mt-1 text-xs text-slate-500">{hint}</p>}
    </div>
  )
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const message = error instanceof Error ? error.message : 'Something went wrong'
  const status = (error as { status?: number } | null)?.status
  // A trace id only helps with a server fault. On a 404 or a rejected request it is noise in front of a message
  // that already says what happened.
  const traceId = status === undefined || status >= 500 ? (error as { traceId?: string } | null)?.traceId : undefined
  return (
    <div role="alert" className="rounded-md bg-red-50 p-4 ring-1 ring-red-200 ring-inset">
      <p className="text-sm font-medium text-red-900">{message}</p>
      {traceId && <p className="mt-1 font-mono text-xs text-red-700">trace {traceId}</p>}
      {onRetry && (
        <Button variant="secondary" className="mt-3" onClick={onRetry}>
          Try again
        </Button>
      )}
    </div>
  )
}

/**
 * Paging for both shapes the API returns: a counted page (`totalElements`/`totalPages`) and an uncounted
 * slice (`hasNext`). The event log is a slice because counting an append-only table costs a full scan for a
 * total that is stale before it is drawn, so this control says "page 3" without pretending to know of how many.
 */
export function Pagination({
  page,
  totalPages,
  totalElements,
  hasNext,
  onPage,
}: {
  page: number
  totalPages?: number
  totalElements?: number
  hasNext?: boolean
  onPage: (page: number) => void
}) {
  const counted = totalElements !== undefined && totalPages !== undefined
  const next = counted ? page + 1 < totalPages : hasNext === true
  return (
    <nav className="mt-4 flex items-center justify-between gap-3 text-sm" aria-label="Pagination">
      <p className="text-slate-600">
        {counted ? (
          <>
            {totalElements} result{totalElements === 1 ? '' : 's'}
            {totalPages > 0 && ` · page ${page + 1} of ${totalPages}`}
          </>
        ) : (
          `page ${page + 1}`
        )}
      </p>
      <div className="flex gap-2">
        <Button variant="secondary" onClick={() => onPage(page - 1)} disabled={page <= 0}>
          Previous
        </Button>
        <Button variant="secondary" onClick={() => onPage(page + 1)} disabled={!next}>
          Next
        </Button>
      </div>
    </nav>
  )
}

/** A short note that labels something as not-real or not-guaranteed, which several pages have to say. */
export function Caveat({ children }: { children: ReactNode }) {
  return (
    <p className="rounded-md bg-amber-50 px-3 py-2 text-xs text-amber-900 ring-1 ring-amber-200 ring-inset">{children}</p>
  )
}

export function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-slate-900/40 p-4">
      <div role="dialog" aria-modal="true" aria-label={title} className="mt-12 w-full max-w-xl rounded-lg bg-white shadow-xl">
        <header className="flex items-center justify-between border-b border-slate-200 px-4 py-3">
          <h2 className="text-sm font-semibold text-slate-900">{title}</h2>
          <Button variant="ghost" onClick={onClose} aria-label="Close">
            ✕
          </Button>
        </header>
        <div className="p-4">{children}</div>
      </div>
    </div>
  )
}
