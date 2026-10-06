import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { fleet } from '../api/endpoints'
import type { DriverStatus } from '../api/types'
import { hasRole } from '../auth/context'
import { useAuth } from '../auth/useAuth'
import { useLiveStream } from '../hooks/useLiveStream'
import { Badge, Button, Card, Cell, EmptyState, ErrorState, Field, Loading, PageHeader, Pagination, Select, Table } from '../ui'
import { coordinates, driverStatusTone, humanize, relative } from '../ui/format'

const STATUSES: DriverStatus[] = ['OFF_SHIFT', 'AVAILABLE', 'BUSY', 'OFFLINE']

export function DriversPage() {
  const { user } = useAuth()
  const staff = hasRole(user, 'ADMIN', 'DISPATCHER')
  const [status, setStatus] = useState<DriverStatus | ''>('')
  const [page, setPage] = useState(0)
  const queryClient = useQueryClient()
  const live = useLiveStream()

  const list = useQuery({
    queryKey: ['drivers', status, page],
    queryFn: () => fleet.drivers({ status, page, size: 20 }),
  })

  const setDriverStatus = useMutation({
    mutationFn: ({ id, next }: { id: number; next: DriverStatus }) => fleet.setDriverStatus(id, next),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['drivers'] }),
  })

  return (
    <>
      <PageHeader
        title="Drivers"
        description="Each driver's shift status, load and last known position. Positions update from the live stream while this page is open."
      />

      {setDriverStatus.error && <div className="mb-4"><ErrorState error={setDriverStatus.error} /></div>}

      <Card>
        <div className="mb-4 flex flex-wrap items-end gap-3">
          <Field label="Status" htmlFor="driver-status">
            <Select
              id="driver-status"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value as DriverStatus | '')
                setPage(0)
              }}
            >
              <option value="">Any</option>
              {STATUSES.map((value) => (
                <option key={value} value={value}>
                  {humanize(value)}
                </option>
              ))}
            </Select>
          </Field>
        </div>

        {list.isPending ? (
          <Loading label="Loading drivers" />
        ) : list.error ? (
          <ErrorState error={list.error} onRetry={() => void list.refetch()} />
        ) : list.data.content.length === 0 ? (
          <EmptyState title="No drivers match this filter" />
        ) : (
          <>
            <Table head={['Driver', 'Status', 'Active', 'Load', 'Last position', '']}>
              {list.data.content.map((driver) => {
                // A position from the stream is newer than the one the list was loaded with, so it wins.
                const streamed = live.positions.get(driver.id)
                const latitude = streamed?.latitude ?? driver.lastLatitude
                const longitude = streamed?.longitude ?? driver.lastLongitude
                const at = streamed?.at ?? driver.lastLocationAt
                return (
                  <tr key={driver.id}>
                    <Cell>
                      <span className="font-medium">{driver.code}</span>
                      <span className="block text-xs text-slate-500">{driver.fullName}</span>
                    </Cell>
                    <Cell>
                      <Badge tone={driverStatusTone(driver.status)}>{humanize(driver.status)}</Badge>
                    </Cell>
                    <Cell>{driver.activeDeliveryCount}</Cell>
                    <Cell className="text-xs">
                      {driver.currentLoadKg} kg · {driver.currentLoadM3} m³
                    </Cell>
                    <Cell className="text-xs">
                      {latitude !== null && longitude !== null ? (
                        <>
                          {coordinates(latitude, longitude)}
                          <span className="block text-slate-500">
                            {relative(at)}
                            {streamed?.source === 'SIMULATION' && ' · simulated'}
                          </span>
                        </>
                      ) : (
                        <span className="text-slate-400">never reported</span>
                      )}
                    </Cell>
                    <Cell>
                      {staff && (
                        <Select
                          aria-label={`Status of ${driver.code}`}
                          value={driver.status}
                          onChange={(e) =>
                            setDriverStatus.mutate({ id: driver.id, next: e.target.value as DriverStatus })
                          }
                        >
                          {STATUSES.map((value) => (
                            <option key={value} value={value}>
                              {humanize(value)}
                            </option>
                          ))}
                        </Select>
                      )}
                    </Cell>
                  </tr>
                )
              })}
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
        {live.positions.size} driver{live.positions.size === 1 ? '' : 's'} have reported a position on the live stream
        since this page was opened. <Button variant="ghost" onClick={() => void list.refetch()}>Reload the list</Button>
      </p>
    </>
  )
}
