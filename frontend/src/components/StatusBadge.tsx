type Tone = 'neutral' | 'success' | 'danger'

const toneClasses: Record<Tone, string> = {
  neutral: 'bg-slate-100 text-slate-700 ring-slate-200',
  success: 'bg-emerald-50 text-emerald-800 ring-emerald-200',
  danger: 'bg-red-50 text-red-800 ring-red-200',
}

interface StatusBadgeProps {
  tone: Tone
  label: string
}

export function StatusBadge({ tone, label }: StatusBadgeProps) {
  return (
    <span
      className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-sm font-medium ring-1 ring-inset ${toneClasses[tone]}`}
    >
      {label}
    </span>
  )
}
