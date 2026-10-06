import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { events, fleet, orders, routing } from '../api/endpoints'
import { SystemStatusCard } from '../components/SystemStatusCard'
import { useLiveStream } from '../hooks/useLiveStream'
import { Badge, Card, Cell, ErrorState, Loading, PageHeader, Stat, Table } from '../ui'
import { dateTime, humanize, orderStatusTone, priorityTone, relative, seconds } from '../ui/format'

/**
 * What a dispatcher wants on opening the app: how much work is waiting, who is free, and what has just gone wrong.
 *
 * Every number here is a count the server returned. Nothing is computed from a sample and nothing is estimated;
 * where a number could be misread (the alert list is only what has arrived since this page was opened) the page
 * says so next to it.
 */
export function DashboardPage() {
  const live = useLiveStream()

  const waiting = useQuery({
    queryKey: ['orders', 'count', 'CREATED'],
    queryFn: () => orders.search({ status: 'CREATED', size: 1 }),
    refetchInterval: 30_000,
  })
  const assigned = useQuery({
    queryKey: ['orders', 'count', 'ASSIGNED'],
    queryFn: () => orders.search({ status: 'ASSIGNED', size: 1 }),
    refetchInterval: 30_000,
  })
  const inTransit = useQuery({
    queryKey: ['orders', 'count', 'IN_TRANSIT'],
    queryFn: () => orders.search({ status: 'IN_TRANSIT', size: 1 }),
    refetchInterval: 30_000,
  })
  const available = useQuery({
    queryKey: ['drivers', 'count', 'AVAILABLE'],
    queryFn: () => fleet.drivers({ status: 'AVAILABLE', size: 1 }),
    refetchInterval: 30_000,
  })
  const newest = useQuery({
    queryKey: ['orders', 'newest'],
    queryFn: () => orders.search({ size: 8 }),
    refetchInterval: 30_000,
  })
  const network = useQuery({ queryKey: ['network'], queryFn: () => routing.network() })
  const recent = useQuery({
    queryKey: ['events', 'recent'],
    queryFn: () => events.search({ size: 8 }),
    refetchInterval: 30_000,
  })

  return (
    <>
      <PageHeader
        title="Dashboard"
        description="Live state of the fleet. Counts come from the server; the alerts below are the ones that have arrived since you opened this page."
      />

      <dl className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="Waiting to assign" value={waiting.data?.totalElements ?? '—'} hint="Orders with status CREATED" />
        <Stat label="Assigned" value={assigned.data?.totalElements ?? '—'} hint="Picked up or on the way counted separately" />
        <Stat label="In transit" value={inTransit.data?.totalElements ?? '—'} />
        <Stat
          label="Drivers available"
          value={available.data?.totalElements ?? '—'}
          hint={`${live.positions.size} driver${live.positions.size === 1 ? '' : 's'} reporting a position`}
        />
      </dl>

      <div className="mt-6 grid gap-6 lg:grid-cols-2">
        <Card
          title="Delay alerts"
          actions={
            <Badge tone={live.connection === 'open' ? 'success' : 'warning'}>
              {live.connection === 'open' ? 'live' : live.connection}
            </Badge>
          }
        >
          {live.alerts.length === 0 ? (
            <p className="text-sm text-slate-600">
              No delivery has been reported late since this page was opened. Earlier alerts are in the{' '}
              <Link to="/events" className="underline">
                event log
              </Link>
              .
            </p>
          ) : (
            <ul className="divide-y divide-slate-100 text-sm">
              {live.alerts.map((alert) => (
                <li key={alert.eventId} className="flex items-center justify-between gap-3 py-2">
                  <span>
                    <Link to={`/orders/${String(alert.payload.orderId)}`} className="font-medium underline">
                      {String(alert.payload.code)}
                    </Link>{' '}
                    <span className="text-slate-600">
                      predicted {seconds(Number(alert.payload.lateBySeconds))} late
                    </span>
                  </span>
                  <span className="text-xs text-slate-500">{relative(alert.occurredAt)}</span>
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card title="Routes recalculated">
          {live.recalculations.length === 0 ? (
            <p className="text-sm text-slate-600">No route has changed since this page was opened.</p>
          ) : (
            <ul className="divide-y divide-slate-100 text-sm">
              {live.recalculations.map((notice) => (
                <li key={notice.eventId} className="py-2">
                  <span className="font-medium">Driver {String(notice.payload.driverId)}</span>{' '}
                  <span className="text-slate-600">
                    {seconds(Number(notice.payload.previousDurationSeconds))} →{' '}
                    {seconds(Number(notice.payload.durationSeconds))}, {String(notice.payload.reason)}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>

      <div className="mt-6 grid gap-6 lg:grid-cols-2">
        <Card title="Newest orders">
          {newest.isPending ? (
            <Loading />
          ) : newest.error ? (
            <ErrorState error={newest.error} onRetry={() => void newest.refetch()} />
          ) : (
            <Table head={['Order', 'Status', 'Priority', 'Created']}>
              {newest.data?.content.map((order) => (
                <tr key={order.id}>
                  <Cell>
                    <Link to={`/orders/${order.id}`} className="font-medium underline">
                      {order.code}
                    </Link>
                  </Cell>
                  <Cell>
                    <Badge tone={orderStatusTone(order.status)}>{humanize(order.status)}</Badge>
                  </Cell>
                  <Cell>
                    <Badge tone={priorityTone(order.priority)}>{humanize(order.priority)}</Badge>
                  </Cell>
                  <Cell className="text-xs">{relative(order.createdAt)}</Cell>
                </tr>
              ))}
            </Table>
          )}
          {newest.data?.content.length === 0 && (
            <p className="text-sm text-slate-600">No orders yet. Create one from the Orders page.</p>
          )}
        </Card>

        <Card title="Recent events">
          {recent.isPending ? (
            <Loading />
          ) : recent.error ? (
            <ErrorState error={recent.error} onRetry={() => void recent.refetch()} />
          ) : (
            <ul className="divide-y divide-slate-100 text-sm">
              {recent.data?.content.map((event) => (
                <li key={event.id} className="flex items-start justify-between gap-3 py-2">
                  <span>
                    <span className="font-mono text-xs text-slate-500">{event.eventType}</span>
                    <span className="block text-slate-700">{event.summary}</span>
                  </span>
                  <span className="shrink-0 text-xs text-slate-500">{relative(event.occurredAt)}</span>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>

      <div className="mt-6">
        <SystemStatusCard />
      </div>

      {network.data && (
        <p className="mt-6 text-xs text-slate-500">
          Road network version {network.data.version}: {network.data.nodes.toLocaleString()} nodes,{' '}
          {network.data.edges.toLocaleString()} edges, {network.data.segmentsWithTraffic.toLocaleString()} segments
          with traffic, source {network.data.source}
          {network.data.synthetic && ' (synthetic city, not real road data)'}. Built {dateTime(network.data.builtAt)}.
        </p>
      )}
    </>
  )
}
