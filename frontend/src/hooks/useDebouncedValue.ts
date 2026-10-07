import { useEffect, useState } from 'react'

/**
 * The value, but only after it has stopped changing for `delayMs`.
 *
 * Used for the text filters on the orders and events pages. Those filters are part of a React Query key, so
 * typing `1234` into one issued four server-side filtered, paged queries — real database work for three
 * results nobody read. The input itself stays controlled by the raw state, so typing is not delayed; only the
 * query key waits.
 */
export function useDebouncedValue<T>(value: T, delayMs = 300): T {
  const [settled, setSettled] = useState(value)

  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), delayMs)
    return () => clearTimeout(timer)
  }, [value, delayMs])

  return settled
}
