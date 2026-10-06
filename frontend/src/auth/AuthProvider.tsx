import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { refreshAccessToken, setAccessToken, setAuthLostHandler } from '../api/client'
import { auth } from '../api/endpoints'
import type { UserResponse } from '../api/types'
import { AuthContext } from './context'

/**
 * Holds who is signed in.
 *
 * On load it tries one refresh: the refresh token is in an HttpOnly cookie, so a reload can recover a session
 * without the access token ever having been written anywhere a script could read. If that fails the user is
 * simply not signed in — the app does not treat it as an error, because it is the normal case for a first visit.
 *
 * The role here is for showing and hiding things. It is never the thing that protects anything: every endpoint
 * checks the role on the server, from the signed token, so a user who edits this state in a debugger gets a 403
 * and not data.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserResponse | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    refreshAccessToken()
      .then(async (token) => {
        if (!token || cancelled) return
        const me = await auth.me().catch(() => null)
        if (!cancelled) setUser(me)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    setAuthLostHandler(() => setUser(null))
    return () => setAuthLostHandler(null)
  }, [])

  const login = useCallback(async (email: string, password: string) => {
    const response = await auth.login(email, password)
    setAccessToken(response.accessToken)
    setUser(response.user)
  }, [])

  const logout = useCallback(async () => {
    // The cookie can only be cleared by the server, so a failed call still has to clear local state.
    await auth.logout().catch(() => undefined)
    setAccessToken(null)
    setUser(null)
  }, [])

  const value = useMemo(() => ({ user, loading, login, logout }), [user, loading, login, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

