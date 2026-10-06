import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { MapContainer, Polyline, TileLayer } from 'react-leaflet'
import 'leaflet/dist/leaflet.css'
import { routing, warehouses } from '../api/endpoints'
import type { OptimizeRequest, RouteRequest } from '../api/endpoints'
import type { OptimizedRoute, RouteMode, RouteResponse } from '../api/types'
import { Badge, Button, Card, Caveat, Cell, ErrorState, Field, Input, PageHeader, Select, Table } from '../ui'
import { clockTime, metres, seconds } from '../ui/format'

interface StopRow {
  label: string
  latitude: string
  longitude: string
}

/**
 * The route planner: one point-to-point route, or a visiting order for several stops.
 *
 * Both halves show the algorithm the server used and whether the answer is provably best, because that is the
 * difference the project is about: A* returns the optimal path, while the stop order is only optimal below the
 * exact threshold and a heuristic above it.
 */
export function PlannerPage() {
  const hubs = useQuery({ queryKey: ['warehouses'], queryFn: () => warehouses.all() })
  const [mode, setMode] = useState<RouteMode>('FASTEST')
  const [from, setFrom] = useState({ latitude: '12.9716', longitude: '77.5946' })
  const [to, setTo] = useState({ latitude: '13.0200', longitude: '77.6600' })
  const [strategy, setStrategy] = useState<'' | 'EXACT' | 'HEURISTIC'>('')
  const [stops, setStops] = useState<StopRow[]>([
    { label: 'Stop 1', latitude: '12.9800', longitude: '77.6100' },
    { label: 'Stop 2', latitude: '13.0100', longitude: '77.6400' },
    { label: 'Stop 3', latitude: '12.9600', longitude: '77.6300' },
  ])

  const single = useMutation({
    mutationFn: (body: RouteRequest) => (mode === 'FASTEST' ? routing.fastest(body) : routing.shortest(body)),
  })
  const optimize = useMutation({
    mutationFn: (body: OptimizeRequest) => routing.optimize(body),
  })

  const route = single.data
  const optimized = optimize.data
  const lines: [number, number][][] = optimized
    ? optimized.legs.map((leg) => leg.path.map(([lat, lon]) => [lat, lon] as [number, number]))
    : route
      ? [route.path.map(([lat, lon]) => [lat, lon] as [number, number])]
      : []

  return (
    <>
      <PageHeader
        title="Route planner"
        description="Routes and stop orders computed on the server's road graph. Every result says which algorithm produced it."
      />

      <div className="grid gap-6 lg:grid-cols-2">
        <Card title="One route">
          <form
            className="space-y-4"
            onSubmit={(event) => {
              event.preventDefault()
              single.mutate({
                from: { latitude: Number(from.latitude), longitude: Number(from.longitude) },
                to: { latitude: Number(to.latitude), longitude: Number(to.longitude) },
              })
            }}
          >
            <Field label="Mode" htmlFor="mode">
              <Select id="mode" value={mode} onChange={(e) => setMode(e.target.value as RouteMode)}>
                <option value="FASTEST">Fastest (travel time, with traffic)</option>
                <option value="SHORTEST">Shortest (distance)</option>
              </Select>
            </Field>
            <div className="grid grid-cols-2 gap-3">
              <Field label="From latitude" htmlFor="from-lat">
                <Input id="from-lat" value={from.latitude} onChange={(e) => setFrom({ ...from, latitude: e.target.value })} />
              </Field>
              <Field label="From longitude" htmlFor="from-lon">
                <Input id="from-lon" value={from.longitude} onChange={(e) => setFrom({ ...from, longitude: e.target.value })} />
              </Field>
              <Field label="To latitude" htmlFor="to-lat">
                <Input id="to-lat" value={to.latitude} onChange={(e) => setTo({ ...to, latitude: e.target.value })} />
              </Field>
              <Field label="To longitude" htmlFor="to-lon">
                <Input id="to-lon" value={to.longitude} onChange={(e) => setTo({ ...to, longitude: e.target.value })} />
              </Field>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Button type="submit" loading={single.isPending}>
                Compute route
              </Button>
              {hubs.data?.slice(0, 3).map((hub) => (
                <Button
                  key={hub.id}
                  type="button"
                  variant="ghost"
                  onClick={() => setFrom({ latitude: String(hub.latitude), longitude: String(hub.longitude) })}
                >
                  From {hub.code}
                </Button>
              ))}
            </div>
          </form>
          {single.error && <div className="mt-4"><ErrorState error={single.error} /></div>}
          {route && <RouteSummary route={route} />}
        </Card>

        <Card title="Several stops">
          <form
            className="space-y-4"
            onSubmit={(event) => {
              event.preventDefault()
              optimize.mutate({
                start: { latitude: Number(from.latitude), longitude: Number(from.longitude) },
                stops: stops.map((stop) => ({
                  label: stop.label,
                  location: { latitude: Number(stop.latitude), longitude: Number(stop.longitude) },
                })),
                mode,
                strategy: strategy === '' ? undefined : strategy,
              })
            }}
          >
            <p className="text-xs text-slate-500">
              The start point is the "From" coordinates on the left. Up to 20 stops; the exact algorithm refuses more
              than 16.
            </p>
            {stops.map((stop, index) => (
              <div key={index} className="grid grid-cols-7 items-end gap-2">
                <div className="col-span-3">
                  <Field label={index === 0 ? 'Label' : ''} htmlFor={`label-${index}`}>
                    <Input
                      id={`label-${index}`}
                      value={stop.label}
                      onChange={(e) => setStops(stops.map((s, i) => (i === index ? { ...s, label: e.target.value } : s)))}
                    />
                  </Field>
                </div>
                <div className="col-span-2">
                  <Field label={index === 0 ? 'Latitude' : ''} htmlFor={`lat-${index}`}>
                    <Input
                      id={`lat-${index}`}
                      value={stop.latitude}
                      onChange={(e) => setStops(stops.map((s, i) => (i === index ? { ...s, latitude: e.target.value } : s)))}
                    />
                  </Field>
                </div>
                <div className="col-span-2">
                  <Field label={index === 0 ? 'Longitude' : ''} htmlFor={`lon-${index}`}>
                    <Input
                      id={`lon-${index}`}
                      value={stop.longitude}
                      onChange={(e) => setStops(stops.map((s, i) => (i === index ? { ...s, longitude: e.target.value } : s)))}
                    />
                  </Field>
                </div>
              </div>
            ))}
            <div className="flex flex-wrap items-end gap-2">
              <Button
                type="button"
                variant="secondary"
                onClick={() =>
                  setStops([...stops, { label: `Stop ${stops.length + 1}`, latitude: '12.99', longitude: '77.62' }])
                }
                disabled={stops.length >= 20}
              >
                Add stop
              </Button>
              <Button type="button" variant="ghost" onClick={() => setStops(stops.slice(0, -1))} disabled={stops.length <= 1}>
                Remove last
              </Button>
              <Field label="Algorithm" htmlFor="strategy">
                <Select id="strategy" value={strategy} onChange={(e) => setStrategy(e.target.value as typeof strategy)}>
                  <option value="">Automatic</option>
                  <option value="EXACT">Exact (Held-Karp)</option>
                  <option value="HEURISTIC">Heuristic (nearest neighbour + 2-opt)</option>
                </Select>
              </Field>
              <Button type="submit" loading={optimize.isPending}>
                Order the stops
              </Button>
            </div>
          </form>
          {optimize.error && <div className="mt-4"><ErrorState error={optimize.error} /></div>}
          {optimized && <OptimizedSummary route={optimized} />}
        </Card>
      </div>

      {lines.length > 0 && (
        <div className="mt-6 h-96 overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm">
          <MapContainer center={[Number(from.latitude), Number(from.longitude)]} zoom={12} className="h-full w-full">
            <TileLayer
              attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
              url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
            />
            {lines.map((line, index) => (
              <Polyline key={index} positions={line} pathOptions={{ color: '#7c3aed', weight: 4 }} />
            ))}
          </MapContainer>
        </div>
      )}
    </>
  )
}

function RouteSummary({ route }: { route: RouteResponse }) {
  return (
    <div className="mt-4 space-y-2 text-sm">
      <p className="font-medium">
        {metres(route.distanceMeters)} · {seconds(route.durationSeconds)}
      </p>
      <div className="flex flex-wrap gap-2">
        <Badge tone="info">{route.algorithm}</Badge>
        <Badge tone={route.optimal ? 'success' : 'warning'}>{route.optimal ? 'optimal' : 'heuristic'}</Badge>
        <Badge tone="neutral">{route.cached ? 'from cache' : `${route.nodesSettled.toLocaleString()} nodes settled`}</Badge>
        <Badge tone="neutral">graph v{route.graphVersion}</Badge>
      </div>
      <p className="text-xs text-slate-500">
        Snapped {metres(route.from.snapDistanceMeters)} and {metres(route.to.snapDistanceMeters)} to the nearest road
        nodes. A large snap distance means the point is nowhere near the network.
      </p>
    </div>
  )
}

function OptimizedSummary({ route }: { route: OptimizedRoute }) {
  return (
    <div className="mt-4 space-y-3">
      <p className="text-sm font-medium">
        {metres(route.totalDistanceMeters)} · {seconds(route.totalDurationSeconds)} driving, finishing{' '}
        {clockTime(route.finishAt)}
      </p>
      <div className="flex flex-wrap gap-2">
        <Badge tone={route.optimal ? 'success' : 'warning'}>
          {route.optimal ? 'proven shortest order' : 'heuristic order'}
        </Badge>
        <Badge tone="info">{route.algorithm}</Badge>
        <Badge tone="neutral">{route.sequencingMillis} ms to order</Badge>
        {route.comparedTo !== null && (
          <Badge tone="neutral">{((route.totalDurationSeconds / route.comparedTo - 1) * 100).toFixed(1)}% vs the other algorithm</Badge>
        )}
      </div>
      <Table head={['#', 'Stop', 'Arrive', 'Depart']}>
        {route.visits.map((visit) => (
          <tr key={visit.sequence}>
            <Cell>{visit.sequence}</Cell>
            <Cell>{visit.label}</Cell>
            <Cell>{clockTime(visit.arriveAt)}</Cell>
            <Cell>{clockTime(visit.departAt)}</Cell>
          </tr>
        ))}
      </Table>
      {!route.optimal && (
        <Caveat>
          This order was produced by nearest neighbour with 2-opt improvement. On the project's own benchmark it
          averages 2 % longer than the proven best order, with a worst case of 19 %.
        </Caveat>
      )}
    </div>
  )
}
