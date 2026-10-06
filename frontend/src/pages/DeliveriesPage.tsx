import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { deliveries } from '../api/endpoints'
import type { OrderResponse, OrderStatus } from '../api/types'
import { Badge, Button, Card, Caveat, EmptyState, ErrorState, Loading, PageHeader } from '../ui'
import { clockTime, dateTime, humanize, metres, orderStatusTone, priorityTone, seconds } from '../ui/format'

/**
 * A driver's own page: the stops in the order the server computed, and the three buttons that move a delivery on.
 *
 * The driver id is never sent from here. Both endpoints take it from the signed token, so this page cannot ask
 * about someone else's deliveries even if the browser is tampered with.
 */
export function DeliveriesPage() {
  const queryClient = useQueryClient()
  const mine = useQuery({ queryKey: ['deliveries', 'mine'], queryFn: () => deliveries.mine() })
  const route = useQuery({
    queryKey: ['deliveries', 'mine', 'route'],
    queryFn: () => deliveries.myRoute(),
    // With nothing to deliver the route endpoint has nothing to answer, so do not ask.
    enabled: (mine.data?.length ?? 0) > 0,
    retry: false,
  })

  const report = useMutation({
    mutationFn: ({ orderId, status }: { orderId: number; status: OrderStatus }) =>
      deliveries.setStatus(orderId, status),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['deliveries'] })
    },
  })

  if (mine.isPending) return <Loading label="Loading your deliveries" />
  if (mine.error) return <ErrorState error={mine.error} onRetry={() => void mine.refetch()} />

  const orders = mine.data
  const arrivalByCode = new Map(route.data?.visits.map((visit) => [visit.label, visit.arriveAt]) ?? [])
  const lateByCode = new Map(route.data?.lateStops.map((late) => [late.label, late.lateBySeconds]) ?? [])

  return (
    <>
      <PageHeader
        title="My deliveries"
        description="Your active deliveries in the order the server suggests visiting them."
      />

      {report.error && <div className="mb-4"><ErrorState error={report.error} /></div>}

      {orders.length === 0 ? (
        <EmptyState title="Nothing assigned to you right now" hint="New deliveries appear here once a dispatcher assigns them." />
      ) : (
        <div className="grid gap-6 lg:grid-cols-3">
          <div className="space-y-4 lg:col-span-2">
            {orders.map((order) => (
              <DeliveryCard
                key={order.id}
                order={order}
                arriveAt={arrivalByCode.get(order.code) ?? null}
                lateBySeconds={lateByCode.get(order.code) ?? null}
                busy={report.isPending && report.variables?.orderId === order.id}
                onReport={(status) => report.mutate({ orderId: order.id, status })}
              />
            ))}
          </div>

          <Card title="Your route">
            {route.isPending ? (
              <Loading label="Computing your stop order" />
            ) : route.error ? (
              <ErrorState error={route.error} />
            ) : (
              <div className="space-y-3 text-sm">
                <p className="font-medium">
                  {route.data.stopCount} stops · {metres(route.data.totalDistanceMeters)} ·{' '}
                  {seconds(route.data.totalDurationSeconds)}
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
                <Caveat>
                  Arrival times assume the current traffic and {Math.round(route.data.serviceSeconds / 60 / Math.max(route.data.stopCount, 1))} minutes
                  at each stop. {route.data.optimal ? 'This is the shortest possible order.' : 'This order is a good one, not a proven best one.'}
                </Caveat>
              </div>
            )}
          </Card>
        </div>
      )}
    </>
  )
}

function DeliveryCard({
  order,
  arriveAt,
  lateBySeconds,
  busy,
  onReport,
}: {
  order: OrderResponse
  arriveAt: string | null
  lateBySeconds: number | null
  busy: boolean
  onReport: (status: OrderStatus) => void
}) {
  // Only the transitions the server accepts from this status are offered.
  const next: OrderStatus[] =
    order.status === 'ASSIGNED'
      ? ['PICKED_UP']
      : order.status === 'PICKED_UP'
        ? ['IN_TRANSIT', 'DELIVERED', 'FAILED']
        : order.status === 'IN_TRANSIT'
          ? ['DELIVERED', 'FAILED']
          : []

  return (
    <Card
      title={
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-semibold">{order.code}</span>
          <Badge tone={orderStatusTone(order.status)}>{humanize(order.status)}</Badge>
          <Badge tone={priorityTone(order.priority)}>{humanize(order.priority)}</Badge>
          {lateBySeconds !== null && <Badge tone="danger">predicted {seconds(lateBySeconds)} late</Badge>}
        </div>
      }
    >
      <div className="space-y-2 text-sm">
        <p className="font-medium">{order.customerName}</p>
        <p className="text-slate-600">{order.dropAddress}</p>
        <p className="text-xs text-slate-500">
          {order.weightKg} kg · {order.volumeM3} m³
          {order.windowEnd && ` · deliver by ${dateTime(order.windowEnd)}`}
          {arriveAt && ` · predicted arrival ${clockTime(arriveAt)}`}
        </p>
        {next.length > 0 && (
          <div className="flex flex-wrap gap-2 pt-2">
            {next.map((status) => (
              <Button
                key={status}
                variant={status === 'FAILED' ? 'danger' : status === 'DELIVERED' ? 'primary' : 'secondary'}
                loading={busy}
                onClick={() => onReport(status)}
              >
                {status === 'PICKED_UP'
                  ? 'Picked up'
                  : status === 'IN_TRANSIT'
                    ? 'On the way'
                    : status === 'DELIVERED'
                      ? 'Delivered'
                      : 'Could not deliver'}
              </Button>
            ))}
          </div>
        )}
      </div>
    </Card>
  )
}
