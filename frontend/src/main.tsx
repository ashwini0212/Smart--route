import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import './index.css'
import { App } from './app/App'
import { AuthProvider } from './auth/AuthProvider'

/**
 * One query client for the whole app.
 *
 * `retry: 0` on purpose: the API client already retries a 401 after refreshing, and retrying a 403 or a 422
 * three times only delays the error the user needs to see. Lists that should keep themselves current say so
 * with their own `refetchInterval`.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: 0, refetchOnWindowFocus: false, staleTime: 10_000 },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  </StrictMode>,
)
