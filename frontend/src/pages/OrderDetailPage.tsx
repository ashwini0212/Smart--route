import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { assignment, orders } from '../api/endpoints'
import { hasRole } from '../auth/context'
import { useAuth } from '../auth/useAuth'
import { Badge, Button, Card, Caveat, Cell, EmptyState, ErrorState, Loading, PageHeader, Table } from '../ui'
import { coordinates, dateTime, humanize, metres, orderStatusTone, priorityTone, seconds } from '../ui/format'

/**
 * One order, and the decision a dispatcher has to make about it: which driver.
 *
 * The candidate list is the server's ranking, shown with its score breakdown rather than as a single number,
 * because the ranking is a weighted heuristic and a dispatcher who can see *why* a driver came first can
 * disagree with it. The exclusion counts are shown for the same reason: "no eligible driver" is only useful
 * if it says what ruled everyone out.
 */
export function OrderDetailPage() {
  const { id } = useParams()
  const orderId = Number(id)
  const { user } = useAuth()
  const staff = hasRole(user, 'ADMIN', 'DISPATCHER')
  const queryClient = useQueryClient()
  const [ranking, setRanking] = useState(false)

  const order = useQuery({ queryKey: ['order', orderId], queryFn: () => orders.byId(orderId) })
  const history = useQuery({ queryKey: ['order', orderId, 'history'], queryFn: () => orders.history(orderId) })
  const candidates = useQuery({
    queryKey: ['order', orderId, 'candidates'],
    queryFn: () => assignment.candidates(orderId, 5),
    enabled: ranking && staff,
  })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['order', orderId] })
    void queryClient.invalidateQueries({ queryKey: ['orders'] })
  }

  const assign = useMutation({
    mutationFn: (driverId: number) => assignment.assign(orderId, driverId, 'Assigned from the dashboard'),
    onSuccess: () => {
      setRanking(false)
      invalidate()
    },
  })
  const unassign = useMutation({
    mutationFn: () => orders.unassign(orderId, 'Unassigned from the dashboard'),
    onSuccess: invalidate,
  })
  const cancel = useMutation({
    mutationFn: () => orders.cancel(orderId, 'Cancelled from the dashboard'),
    onSuccess: invalidate,
  })

  if (order.isPending) return <Loading label="Loading order" />
  if (order.error) return <ErrorState error={order.error} onRetry={() => void order.refetch()} />

  const o = order.data
  return (
    <>
      <PageHeader
        title={o.code}
        description={`${o.customerName} · ${o.dropAddress}`}
        actions={
          staff && (
            <div className="flex flex-wrap gap-2">
              {o.status === 'CREATED' && (
                <Button onClick={() => setRanking(true)} loading={candidates.isPending && ranking}>
                  Find drivers
                </Button>
              )}
              {o.status === 'ASSIGNED' && (
                <Button variant="secondary" loading={unassign.isPending} onClick={() => unassign.mutate()}>
                  Unassign
                </Button>
              )}
              {(o.status === 'CREATED' || o.status === 'ASSIGNED') && (
                <Button variant="danger" loading={cancel.isPending} onClick={() => cancel.mutate()}>
                  Cancel order
                </Button>
              )}
            </div>
          )
        }
      />

      {(unassign.error || cancel.error || assign.error) && (
        <div className="mb-4">
          <ErrorState error={unassign.error ?? cancel.error ?? assign.error} />
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-3">
        <Card title="Order">
          <dl className="space-y-2 text-sm">
            <Row label="Status">
              <Badge tone={orderStatusTone(o.status)}>{humanize(o.status)}</Badge>
            </Row>
            <Row label="Priority">
              <Badge tone={priorityTone(o.priority)}>{humanize(o.priority)}</Badge>
            </Row>
            <Row label="Weight / volume">
              {o.weightKg} kg · {o.volumeM3} m³
            </Row>
            <Row label="Requires">{o.requiredVehicleType ? humanize(o.requiredVehicleType) : 'any vehicle'}</Row>
            <Row label="Drop">{coordinates(o.dropLatitude, o.dropLongitude)}</Row>
            <Row label="Deliver by">{dateTime(o.windowEnd)}</Row>
            <Row label="Driver">
              {o.driverId ? (
                <Link to={`/drivers?driverId=${o.driverId}`} className="underline">
                  Driver {o.driverId}
                </Link>
              ) : (
                'unassigned'
              )}
            </Row>
            <Row label="Created">{dateTime(o.createdAt)}</Row>
          </dl>
        </Card>

        <div className="lg:col-span-2">
          <Card title="Driver candidates">
            {!ranking ? (
              <p className="text-sm text-slate-600">
                {o.status === 'CREATED'
                  ? 'Choose "Find drivers" to rank the drivers near the pickup warehouse.'
                  : 'Candidates are ranked for orders that are still waiting.'}
              </p>
            ) : candidates.isPending ? (
              <Loading label="Ranking drivers" />
            ) : candidates.error ? (
              <ErrorState error={candidates.error} onRetry={() => void candidates.refetch()} />
            ) : candidates.data.candidates.length === 0 ? (
              <EmptyState
                title="No eligible driver"
                hint={`${candidates.data.driversInRadius} driver(s) in radius; ruled out: ${
                  Object.entries(candidates.data.excluded)
                    .map(([reason, count]) => `${count} ${humanize(reason).toLowerCase()}`)
                    .join(', ') || 'none'
                }`}
              />
            ) : (
              <>
                <Table head={['#', 'Driver', 'ETA', 'Score', 'Breakdown', 'Load', '']}>
                  {candidates.data.candidates.map((candidate) => (
                    <tr key={candidate.driverId}>
                      <Cell>{candidate.rank}</Cell>
                      <Cell>
                        <span className="font-medium">{candidate.driverCode}</span>
                        <span className="block text-xs text-slate-500">{humanize(candidate.vehicleType)}</span>
                      </Cell>
                      <Cell>
                        {seconds(candidate.etaSeconds)}
                        <span className="block text-xs text-slate-500">{metres(candidate.straightLineMeters)} direct</span>
                      </Cell>
                      <Cell className="font-medium">{candidate.score.toFixed(3)}</Cell>
                      <Cell className="text-xs">
                        eta {candidate.etaScore.toFixed(2)} · load {candidate.workloadScore.toFixed(2)} · fit{' '}
                        {candidate.capacityScore.toFixed(2)}
                      </Cell>
                      <Cell className="text-xs">
                        {candidate.activeDeliveries} active · {candidate.remainingKg} kg free
                      </Cell>
                      <Cell>
                        <Button
                          loading={assign.isPending && assign.variables === candidate.driverId}
                          onClick={() => assign.mutate(candidate.driverId)}
                        >
                          Assign
                        </Button>
                      </Cell>
                    </tr>
                  ))}
                </Table>
                <div className="mt-4 space-y-2">
                  <Caveat>
                    Ranking: {candidates.data.algorithm}. The ETA is an optimal travel time on the current road
                    network; the overall score is a weighted judgement, so a different weighting would order these
                    drivers differently.
                  </Caveat>
                  <p className="text-xs text-slate-500">
                    {candidates.data.driversInRadius} drivers within the search radius, {candidates.data.eligible}{' '}
                    eligible after the hard rules.
                  </p>
                </div>
              </>
            )}
          </Card>
        </div>
      </div>

      <div className="mt-6">
        <Card title="Status history">
          {history.isPending ? (
            <Loading />
          ) : history.error ? (
            <ErrorState error={history.error} />
          ) : (
            <Table head={['From', 'To', 'Reason', 'When']}>
              {history.data?.map((change, index) => (
                <tr key={index}>
                  <Cell>{change.fromStatus ? humanize(change.fromStatus) : '—'}</Cell>
                  <Cell>
                    <Badge tone={orderStatusTone(change.toStatus)}>{humanize(change.toStatus)}</Badge>
                  </Cell>
                  <Cell>{change.reason ?? '—'}</Cell>
                  <Cell className="text-xs">{dateTime(change.changedAt)}</Cell>
                </tr>
              ))}
            </Table>
          )}
        </Card>
      </div>
    </>
  )
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <dt className="text-slate-500">{label}</dt>
      <dd className="text-right text-slate-900">{children}</dd>
    </div>
  )
}
