import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Route, Routes } from 'react-router-dom'
import { RequireAuth } from './RequireAuth'
import { renderWithProviders, testUser } from '../test/render'

function routes() {
  return (
    <Routes>
      <Route path="/login" element={<p>Sign in</p>} />
      <Route
        path="/admin"
        element={
          <RequireAuth roles={['ADMIN']}>
            <p>Admin tools</p>
          </RequireAuth>
        }
      />
      <Route
        path="/"
        element={
          <RequireAuth>
            <p>Dashboard</p>
          </RequireAuth>
        }
      />
    </Routes>
  )
}

describe('route guard', () => {
  it('waits instead of redirecting while the session is being restored', () => {
    renderWithProviders(routes(), { user: null, loading: true })

    expect(screen.getByText(/Restoring your session/)).toBeInTheDocument()
    expect(screen.queryByText('Sign in')).not.toBeInTheDocument()
  })

  it('sends a signed-out visitor to the login page', () => {
    renderWithProviders(routes(), { user: null })

    expect(screen.getByText('Sign in')).toBeInTheDocument()
  })

  it('lets a signed-in user through', () => {
    renderWithProviders(routes(), { user: testUser('DISPATCHER') })

    expect(screen.getByText('Dashboard')).toBeInTheDocument()
  })

  it('explains, rather than redirecting, when the role is wrong', () => {
    renderWithProviders(routes(), { user: testUser('DISPATCHER'), route: '/admin' })

    expect(screen.getByText('Not available for your role')).toBeInTheDocument()
    expect(screen.queryByText('Admin tools')).not.toBeInTheDocument()
  })

  it('allows the role the page is for', () => {
    renderWithProviders(routes(), { user: testUser('ADMIN'), route: '/admin' })

    expect(screen.getByText('Admin tools')).toBeInTheDocument()
  })
})
