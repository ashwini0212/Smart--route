import { Suspense, lazy } from 'react'
import { Route, Routes } from 'react-router-dom'
import { AppLayout } from './AppLayout'
import { RequireAuth } from './RequireAuth'
import { AdminPage } from '../pages/AdminPage'
import { AnalyticsPage } from '../pages/AnalyticsPage'
import { DashboardPage } from '../pages/DashboardPage'
import { DeliveriesPage } from '../pages/DeliveriesPage'
import { DriversPage } from '../pages/DriversPage'
import { EventsPage } from '../pages/EventsPage'
import { LoginPage } from '../pages/LoginPage'
import { OrderDetailPage } from '../pages/OrderDetailPage'
import { OrdersPage } from '../pages/OrdersPage'
import { VehiclesPage } from '../pages/VehiclesPage'
import { Card, Loading } from '../ui'

// Leaflet is a third of the bundle, and only these two pages use it: loaded when one of them is opened.
const LiveMapPage = lazy(() => import('../pages/LiveMapPage').then((m) => ({ default: m.LiveMapPage })))
const PlannerPage = lazy(() => import('../pages/PlannerPage').then((m) => ({ default: m.PlannerPage })))

/**
 * The route table.
 *
 * The role lists here decide what a user is shown. They are deliberately the same roles the matching endpoints
 * require, so a user is not offered a page that can only answer 403 — but the server is what enforces them.
 */
export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        element={
          <RequireAuth>
            <AppLayout />
          </RequireAuth>
        }
      >
        <Route index element={<DashboardPage />} />
        <Route
          path="map"
          element={
            <Suspense fallback={<Loading label="Loading the map" />}>
              <LiveMapPage />
            </Suspense>
          }
        />
        <Route path="orders" element={<OrdersPage />} />
        <Route path="orders/:id" element={<OrderDetailPage />} />
        <Route path="drivers" element={<DriversPage />} />
        <Route path="vehicles" element={<VehiclesPage />} />
        <Route
          path="planner"
          element={
            <Suspense fallback={<Loading label="Loading the planner" />}>
              <PlannerPage />
            </Suspense>
          }
        />
        <Route
          path="deliveries"
          element={
            <RequireAuth roles={['DRIVER']}>
              <DeliveriesPage />
            </RequireAuth>
          }
        />
        <Route path="events" element={<EventsPage />} />
        <Route
          path="admin"
          element={
            <RequireAuth roles={['ADMIN']}>
              <AdminPage />
            </RequireAuth>
          }
        />
        <Route path="analytics" element={<AnalyticsPage />} />
        <Route path="*" element={<Card title="Page not found">That address does not exist in SmartRoute.</Card>} />
      </Route>
    </Routes>
  )
}
