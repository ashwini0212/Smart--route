import { SystemStatusCard } from './components/SystemStatusCard'

function App() {
  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-5xl items-center gap-3 px-4 py-4">
          <img src="/favicon.svg" alt="" className="h-7 w-7" />
          <h1 className="text-lg font-semibold">SmartRoute</h1>
        </div>
      </header>
      <main className="mx-auto max-w-5xl px-4 py-8">
        <p className="mb-6 max-w-2xl text-sm text-slate-600">
          Logistics and route optimization platform. This is the Phase 1 shell; dashboard pages arrive in later phases.
        </p>
        <SystemStatusCard />
      </main>
    </div>
  )
}

export default App
