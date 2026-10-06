import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { analytics } from '../api/endpoints'
import type { ThroughputDay } from '../api/types'
import { Badge, Card, Cell, EmptyState, ErrorState, Field, Loading, PageHeader, Select, Stat, Table } from '../ui'
import { humanize } from '../ui/format'

const WINDOWS = [7, 14, 30, 90]

/**
 * The analytics page (FR-23).
 *
 * Every number here comes from the server, and so does every definition printed under it: the API returns the
 * sentence it computed the number with. That is deliberate — a dashboard where "on-time rate" means whatever
 * the reader assumes is worse than no dashboard, and a definition kept in the frontend would drift from the
 * query that produces it.
 */
export function AnalyticsPage() {
  const [days, setDays] = useState(7)

  const overview = useQuery({ queryKey: ['analytics', 'overview', days], queryFn: () => analytics.overview(days) })
  const throughput = useQuery({ queryKey: ['analytics', 'throughput', days], queryFn: () => analytics.throughput(days) })
  const fleet = useQuery({ queryKey: ['analytics', 'fleet', days], queryFn: () => analytics.fleet(days) })
  const eta = useQuery({ queryKey: ['analytics', 'eta', days], queryFn: () => analytics.etaAccuracy(days) })

  return (
    <>
      <PageHeader
        title="Analytics"
        description="Aggregates over what the system recorded. Each card prints the definition the server computed its numbers with."
        actions={
          <Field label="Window" htmlFor="days">
            <Select id="days" value={days} onChange={(e) => setDays(Number(e.target.value))}>
              {WINDOWS.map((value) => (
                <option key={value} value={value}>
                  Last {value} days
                </option>
              ))}
            </Select>
          </Field>
        }
      />

      {overview.isPending ? (
        <Loading label="Loading analytics" />
      ) : overview.error ? (
        <ErrorState error={overview.error} onRetry={() => void overview.refetch()} />
      ) : (
        <>
          <dl className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <Stat label="Orders created" value={overview.data.created} hint={`In the last ${days} days`} />
            <Stat label="Delivered" value={overview.data.delivered} hint={`${overview.data.failed} failed, ${overview.data.cancelled} cancelled`} />
            <Stat
              label="On-time rate"
              value={overview.data.onTimeRate === null ? 'no data' : `${(overview.data.onTimeRate * 100).toFixed(1)}%`}
              hint={
                overview.data.onTimeRate === null
                  ? 'Nothing delivered in this window had a delivery window'
                  : `${overview.data.onTime} of ${overview.data.deliveredWithWindow} deliveries that had a window`
              }
            />
            <Stat
              label="Assignment to delivery"
              value={
                overview.data.assignedToDeliveredMinutes.p50 === null
                  ? 'no data'
                  : `${overview.data.assignedToDeliveredMinutes.p50} min`
              }
              hint={
                overview.data.assignedToDeliveredMinutes.p90 === null
                  ? undefined
                  : `median; 90th percentile ${overview.data.assignedToDeliveredMinutes.p90} min over ${overview.data.assignedToDeliveredMinutes.samples} deliveries`
              }
            />
          </dl>

          <div className="mt-6 grid gap-6 lg:grid-cols-3">
            <div className="lg:col-span-2">
              <Card title={`Orders per day (last ${days} days, UTC)`}>
                {throughput.isPending ? (
                  <Loading />
                ) : throughput.error ? (
                  <ErrorState error={throughput.error} />
                ) : (
                  <ThroughputChart days={throughput.data} />
                )}
              </Card>
            </div>

            <Card title="Right now">
              <dl className="space-y-2 text-sm">
                <Line label="Waiting for a driver" value={overview.data.waitingNow} />
                <Line label="Deliveries under way" value={overview.data.activeNow} />
                {Object.entries(overview.data.ordersByStatus).map(([status, count]) => (
                  <Line key={status} label={humanize(status)} value={count} muted />
                ))}
              </dl>
              <p className="mt-3 text-xs text-slate-500">
                The breakdown counts orders created in the window, by the status they are in now.
              </p>
            </Card>
          </div>

          <div className="mt-6 grid gap-6 lg:grid-cols-2">
            <Card title="Fleet">
              {fleet.isPending ? (
                <Loading />
              ) : fleet.error ? (
                <ErrorState error={fleet.error} />
              ) : (
                <>
                  <div className="mb-4 flex flex-wrap gap-2">
                    <Badge tone="neutral">{fleet.data.driverCount} drivers</Badge>
                    <Badge tone="info">{fleet.data.driversWithDeliveries} delivered something</Badge>
                    {fleet.data.deliveriesPerActiveDriver !== null && (
                      <Badge tone="neutral">{fleet.data.deliveriesPerActiveDriver} deliveries each</Badge>
                    )}
                  </div>
                  {fleet.data.perDriver.length === 0 ? (
                    <EmptyState title="No deliveries were completed in this window" />
                  ) : (
                    <Table head={['Driver', 'Delivered', 'Late', 'Failed', 'Median']}>
                      {fleet.data.perDriver.map((driver) => (
                        <tr key={driver.driverId}>
                          <Cell>
                            <span className="font-medium">{driver.driverCode}</span>
                            <span className="block text-xs text-slate-500">{humanize(driver.status)}</span>
                          </Cell>
                          <Cell>{driver.delivered}</Cell>
                          <Cell>{driver.late > 0 ? <Badge tone="warning">{driver.late}</Badge> : 0}</Cell>
                          <Cell>{driver.failed}</Cell>
                          <Cell className="text-xs">{driver.medianMinutes === null ? '—' : `${driver.medianMinutes} min`}</Cell>
                        </tr>
                      ))}
                    </Table>
                  )}
                  <Definitions items={fleet.data.definitions} />
                </>
              )}
            </Card>

            <Card title="Predicted pickup time against what happened">
              {eta.isPending ? (
                <Loading />
              ) : eta.error ? (
                <ErrorState error={eta.error} />
              ) : eta.data.samples === 0 ? (
                <EmptyState title="No pickups in this window to compare" hint="A pickup needs an assignment with an ETA and a reported PICKED_UP." />
              ) : (
                <>
                  <dl className="space-y-2 text-sm">
                    <Line label="Pickups compared" value={eta.data.samples} />
                    <Line label="Predicted (median)" value={`${eta.data.predictedMedianMinutes} min`} />
                    <Line label="Actual (median)" value={`${eta.data.actualMedianMinutes} min`} />
                    <Line label="Difference (median)" value={`${eta.data.medianDifferenceMinutes} min`} />
                    <Line label="Difference (90th percentile)" value={`${eta.data.p90DifferenceMinutes} min`} />
                    <Line label="Within five minutes" value={eta.data.withinFiveMinutes} />
                  </dl>
                  <Definitions items={eta.data.definitions} />
                </>
              )}
            </Card>
          </div>

          <div className="mt-6">
            <Card title="How these numbers are defined">
              <Definitions items={overview.data.definitions} />
            </Card>
          </div>
        </>
      )}
    </>
  )
}

function Line({ label, value, muted = false }: { label: string; value: React.ReactNode; muted?: boolean }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <dt className={muted ? 'text-slate-400' : 'text-slate-500'}>{label}</dt>
      <dd className={muted ? 'text-slate-600' : 'font-medium text-slate-900'}>{value}</dd>
    </div>
  )
}

function Definitions({ items }: { items: string[] }) {
  return (
    <ul className="mt-4 space-y-1 text-xs text-slate-500">
      {items.map((item) => (
        <li key={item}>· {item}</li>
      ))}
    </ul>
  )
}

/**
 * A bar chart drawn as SVG.
 *
 * No chart library: this is one chart, and a library would add more to the bundle than the whole page weighs.
 * The y-axis starts at zero and the maximum is printed, because a bar chart with a cropped axis exaggerates
 * every difference on it.
 */
function ThroughputChart({ days }: { days: ThroughputDay[] }) {
  const max = Math.max(1, ...days.map((day) => Math.max(day.created, day.delivered)))
  const width = 100 / Math.max(days.length, 1)

  if (days.every((day) => day.created === 0 && day.delivered === 0)) {
    return <EmptyState title="Nothing was created or delivered in this window" />
  }

  return (
    <figure>
      <svg viewBox="0 0 100 42" className="h-56 w-full" role="img" aria-label="Orders created and delivered per day">
        {days.map((day, index) => {
          const x = index * width
          const createdHeight = (day.created / max) * 34
          const deliveredHeight = (day.delivered / max) * 34
          return (
            <g key={day.day}>
              <rect x={x + width * 0.15} y={36 - createdHeight} width={width * 0.32} height={createdHeight} fill="#0ea5e9">
                <title>{`${day.day}: ${day.created} created`}</title>
              </rect>
              <rect x={x + width * 0.52} y={36 - deliveredHeight} width={width * 0.32} height={deliveredHeight} fill="#10b981">
                <title>{`${day.day}: ${day.delivered} delivered`}</title>
              </rect>
              <text x={x + width / 2} y={41} textAnchor="middle" fontSize="2.6" fill="#64748b">
                {day.day.slice(5)}
              </text>
            </g>
          )
        })}
        <line x1="0" y1="36" x2="100" y2="36" stroke="#cbd5e1" strokeWidth="0.3" />
      </svg>
      <figcaption className="mt-2 flex flex-wrap items-center gap-4 text-xs text-slate-500">
        <span className="flex items-center gap-1">
          <span className="inline-block h-2 w-2 rounded-sm bg-sky-500" /> created
        </span>
        <span className="flex items-center gap-1">
          <span className="inline-block h-2 w-2 rounded-sm bg-emerald-500" /> delivered
        </span>
        <span>axis starts at zero; tallest bar is {max}</span>
      </figcaption>
    </figure>
  )
}
