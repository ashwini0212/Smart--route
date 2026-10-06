import { useEffect, useMemo, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { CircleMarker, MapContainer, Polyline, Popup, TileLayer, useMap } from 'react-leaflet'
import 'leaflet/dist/leaflet.css'
import { deliveries, routing, tracking, warehouses } from '../api/endpoints'
import type { LivePosition } from '../api/types'
import { useLiveStream } from '../hooks/useLiveStream'
import { Badge, Card, Caveat, ErrorState, Loading, PageHeader } from '../ui'
import { clockTime, coordinates, metres, relative, seconds } from '../ui/format'

/**
 * The live map (FR-19).
 *
 * Positions come from two places and neither is invented in the browser: one GET of the read model when the page
 * opens, then the server-sent event stream for every change after that. Nothing here interpolates a driver
 * between two reports, because a line drawn by the client is a guess the user cannot distinguish from a
 * measurement — a marker that only moves when the server says so is less smooth and more honest.
 */
export function LiveMapPage() {
  const live = useLiveStream()
  const [selected, setSelected] = useState<number | null>(null)

  const initial = useQuery({ queryKey: ['positions'], queryFn: () => tracking.positions() })
  const network = useQuery({ queryKey: ['network'], queryFn: () => routing.network() })
  const hubs = useQuery({ queryKey: ['warehouses'], queryFn: () => warehouses.all() })
  const route = useQuery({
    queryKey: ['driver-route', selected],
    queryFn: () => deliveries.routeFor(selected as number),
    enabled: selected !== null,
    retry: false,
  })

  // The stream's positions win over the loaded ones; a driver only in the first load keeps that position.
  const positions = useMemo(() => {
    const merged = new Map<number, LivePosition>()
    initial.data?.forEach((position) => merged.set(position.driverId, position))
    live.positions.forEach((position, id) => {
      const known = merged.get(id)
      if (!known || Date.parse(position.at) >= Date.parse(known.at)) merged.set(id, position)
    })
    return [...merged.values()]
  }, [initial.data, live.positions])

  const simulated = positions.filter((p) => p.source === 'SIMULATION').length
  const centre: [number, number] = network.data
    ? [
        (network.data.bounds.minLatitude + network.data.bounds.maxLatitude) / 2,
        (network.data.bounds.minLongitude + network.data.bounds.maxLongitude) / 2,
      ]
    : [12.9716, 77.5946]

  return (
    <>
      <PageHeader
        title="Live map"
        description="Driver positions pushed from the server. Click a driver to load the stop order for their remaining deliveries."
        actions={
          <div className="flex items-center gap-2">
            <Badge tone={live.connection === 'open' ? 'success' : 'warning'}>
              {live.connection === 'open' ? 'stream open' : live.connection}
            </Badge>
            <Badge tone="neutral">{positions.length} drivers</Badge>
            {simulated > 0 && <Badge tone="warning">{simulated} simulated</Badge>}
          </div>
        }
      />

      {initial.error && <div className="mb-4"><ErrorState error={initial.error} onRetry={() => void initial.refetch()} /></div>}

      <div className="grid gap-6 lg:grid-cols-4">
        <div className="lg:col-span-3">
          <div className="h-[32rem] overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm">
            {initial.isPending ? (
              <Loading label="Loading positions" />
            ) : (
              <MapContainer center={centre} zoom={13} className="h-full w-full" scrollWheelZoom>
                <TileLayer
                  attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
                  url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
                />
                <FitToPositions positions={positions} />
                {hubs.data?.map((hub) => (
                  <CircleMarker
                    key={`hub-${hub.id}`}
                    center={[hub.latitude, hub.longitude]}
                    radius={7}
                    pathOptions={{ color: '#0f172a', fillColor: '#0f172a', fillOpacity: 0.9 }}
                  >
                    <Popup>
                      <strong>{hub.code}</strong>
                      <br />
                      {hub.name}
                    </Popup>
                  </CircleMarker>
                ))}
                {positions.map((position) => (
                  <CircleMarker
                    key={position.driverId}
                    center={[position.latitude, position.longitude]}
                    radius={6}
                    pathOptions={{
                      // Simulated positions are a different colour as well as labelled, so the map itself says so.
                      color: position.source === 'SIMULATION' ? '#b45309' : '#0369a1',
                      fillColor: position.source === 'SIMULATION' ? '#f59e0b' : '#0ea5e9',
                      fillOpacity: 0.9,
                    }}
                    eventHandlers={{ click: () => setSelected(position.driverId) }}
                  >
                    <Popup>
                      <strong>Driver {position.driverId}</strong>
                      <br />
                      {coordinates(position.latitude, position.longitude)}
                      <br />
                      reported {relative(position.at)}
                      <br />
                      source {position.source}
                    </Popup>
                  </CircleMarker>
                ))}
                {route.data?.legs.map((leg) => (
                  <Polyline
                    key={`${leg.fromSequence}-${leg.toSequence}`}
                    positions={leg.path.map(([lat, lon]) => [lat, lon] as [number, number])}
                    pathOptions={{ color: '#7c3aed', weight: 4, opacity: 0.8 }}
                  />
                ))}
              </MapContainer>
            )}
          </div>
          <div className="mt-3">
            <Caveat>
              Markers move only when the server reports a new position; nothing is interpolated between reports. Tiles
              are from OpenStreetMap, while routing runs on{' '}
              {network.data?.synthetic ? 'a synthetic road graph, so routes will not match the streets you see' : 'the imported road graph'}.
            </Caveat>
          </div>
        </div>

        <div className="space-y-4">
          <Card title="Selected driver">
            {selected === null ? (
              <p className="text-sm text-slate-600">Click a driver on the map.</p>
            ) : route.isPending ? (
              <Loading label="Computing stop order" />
            ) : route.error ? (
              <ErrorState error={route.error} />
            ) : (
              <div className="space-y-2 text-sm">
                <p className="font-medium">Driver {selected}</p>
                <p className="text-slate-600">
                  {route.data.stopCount} stop{route.data.stopCount === 1 ? '' : 's'} ·{' '}
                  {metres(route.data.totalDistanceMeters)} · {seconds(route.data.totalDurationSeconds)}
                </p>
                <ol className="space-y-1">
                  {route.data.visits.map((visit) => (
                    <li key={visit.sequence} className="flex justify-between gap-2">
                      <span>
                        {visit.sequence}. {visit.label}
                      </span>
                      <span className="text-slate-500">{clockTime(visit.arriveAt)}</span>
                    </li>
                  ))}
                </ol>
                {route.data.lateStops.length > 0 && (
                  <p className="text-red-700">
                    {route.data.lateStops.length} stop(s) predicted late:{' '}
                    {route.data.lateStops.map((late) => late.label).join(', ')}
                  </p>
                )}
                <p className="text-xs text-slate-500">
                  {route.data.algorithm}
                  {route.data.optimal ? ' (proven shortest order)' : ' (heuristic order, not proven shortest)'}
                </p>
              </div>
            )}
          </Card>

          <Card title="Since this page opened">
            <dl className="space-y-1 text-sm">
              <div className="flex justify-between">
                <dt className="text-slate-500">Frames received</dt>
                <dd>{live.framesReceived}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-slate-500">Last frame</dt>
                <dd>{live.lastFrameAt ? relative(new Date(live.lastFrameAt).toISOString()) : 'none yet'}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-slate-500">Delay alerts</dt>
                <dd>{live.alerts.length}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-slate-500">Recalculations</dt>
                <dd>{live.recalculations.length}</dd>
              </div>
            </dl>
          </Card>
        </div>
      </div>
    </>
  )
}

/** Fits the view to the drivers once, when the first batch arrives; later frames must not move the map under the user. */
function FitToPositions({ positions }: { positions: LivePosition[] }) {
  const map = useMap()
  // A ref, not state: fitting the map is a side effect on Leaflet, and nothing here needs to re-render for it.
  const fitted = useRef(false)
  useEffect(() => {
    if (fitted.current || positions.length === 0) return
    const latitudes = positions.map((p) => p.latitude)
    const longitudes = positions.map((p) => p.longitude)
    map.fitBounds(
      [
        [Math.min(...latitudes), Math.min(...longitudes)],
        [Math.max(...latitudes), Math.max(...longitudes)],
      ],
      { padding: [40, 40], maxZoom: 14 },
    )
    fitted.current = true
  }, [map, positions])
  return null
}
