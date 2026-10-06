import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { events } from '../api/endpoints'
import { hasRole } from '../auth/context'
import { useAuth } from '../auth/useAuth'
import { Badge, Card, Cell, EmptyState, ErrorState, Field, Input, Loading, PageHeader, Pagination, Select, Table } from '../ui'
import { dateTime, relative } from '../ui/format'

const TYPES = [
  'ORDER_CREATED',
  'ORDER_ASSIGNED',
  'ORDER_UNASSIGNED',
  'DELIVERY_STARTED',
  'DELIVERY_COMPLETED',
  'DELIVERY_FAILED',
  'DELIVERY_DELAYED',
  'ROUTE_RECALCULATED',
  'DRIVER_LOCATION_UPDATED',
]

/**
 * The recorded event stream: what actually happened, in the order the consumer recorded it.
 *
 * This is the page that makes the event pipeline inspectable instead of a claim — including the outbox depth,
 * which is how you tell that the relay has stopped keeping up.
 */
export function EventsPage() {
  const { user } = useAuth()
  const admin = hasRole(user, 'ADMIN')
  const [eventType, setEventType] = useState('')
  const [aggregateId, setAggregateId] = useState('')
  const [page, setPage] = useState(0)
  const [expanded, setExpanded] = useState<number | null>(null)

  const list = useQuery({
    queryKey: ['events', eventType, aggregateId, page],
    queryFn: () => events.search({ eventType, aggregateId, page, size: 20 }),
    refetchInterval: 15_000,
  })
  const outbox = useQuery({
    queryKey: ['outbox'],
    queryFn: () => events.outbox(),
    enabled: admin,
    refetchInterval: 15_000,
  })

  return (
    <>
      <PageHeader
        title="Events"
        description="Every domain event the consumer has recorded, newest first. Driver positions are events too, so this list is mostly movement when the simulators are on."
      />

      {admin && outbox.data && (
        <p className="mb-4 rounded-md bg-slate-100 px-3 py-2 text-sm text-slate-700">
          Outbox: {outbox.data.pending.toLocaleString()} waiting to publish,{' '}
          {outbox.data.published.toLocaleString()} published. A pending count that keeps growing means the relay is
          stuck, not that the system is busy.
        </p>
      )}

      <Card>
        <div className="mb-4 flex flex-wrap items-end gap-3">
          <Field label="Type" htmlFor="event-type">
            <Select
              id="event-type"
              value={eventType}
              onChange={(e) => {
                setEventType(e.target.value)
                setPage(0)
              }}
            >
              <option value="">Any</option>
              {TYPES.map((type) => (
                <option key={type} value={type}>
                  {type}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="About id" htmlFor="aggregate-id" hint="An order id or a driver id">
            <Input
              id="aggregate-id"
              value={aggregateId}
              onChange={(e) => {
                setAggregateId(e.target.value)
                setPage(0)
              }}
            />
          </Field>
        </div>

        {list.isPending ? (
          <Loading label="Loading events" />
        ) : list.error ? (
          <ErrorState error={list.error} onRetry={() => void list.refetch()} />
        ) : list.data.content.length === 0 ? (
          <EmptyState title="No events match these filters" hint="Events appear once the relay publishes them." />
        ) : (
          <>
            <Table head={['Type', 'About', 'Summary', 'When', '']}>
              {list.data.content.map((event) => (
                <tr key={event.id}>
                  <Cell>
                    <span className="font-mono text-xs">{event.eventType}</span>
                    <span className="block text-xs text-slate-500">v{event.eventVersion}</span>
                  </Cell>
                  <Cell className="text-xs">
                    {event.aggregateType} {event.aggregateId}
                  </Cell>
                  <Cell>
                    {event.summary}
                    {expanded === event.id && (
                      <pre className="mt-2 max-w-xl overflow-x-auto rounded bg-slate-50 p-2 text-xs text-slate-700">
                        {prettyJson(event.payload)}
                      </pre>
                    )}
                  </Cell>
                  <Cell className="text-xs">
                    {relative(event.occurredAt)}
                    <span className="block text-slate-500">{dateTime(event.occurredAt)}</span>
                  </Cell>
                  <Cell>
                    <button
                      type="button"
                      className="text-xs underline"
                      onClick={() => setExpanded(expanded === event.id ? null : event.id)}
                    >
                      {expanded === event.id ? 'Hide payload' : 'Payload'}
                    </button>
                  </Cell>
                </tr>
              ))}
            </Table>
            <Pagination
              page={list.data.page}
              totalPages={list.data.totalPages}
              totalElements={list.data.totalElements}
              onPage={setPage}
            />
          </>
        )}
      </Card>

      <p className="mt-4 text-xs text-slate-500">
        An event appears here after the relay has published it and the consumer has recorded it, which is about half a
        second behind the change by default. <Badge tone="neutral">at-least-once delivery, recorded exactly once</Badge>
      </p>
    </>
  )
}

function prettyJson(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    // The payload is stored as JSONB; if it ever is not valid JSON, showing it raw beats showing nothing.
    return raw
  }
}
