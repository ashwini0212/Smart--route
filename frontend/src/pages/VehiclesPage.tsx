import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { fleet } from '../api/endpoints'
import type { VehicleType } from '../api/types'
import { Badge, Card, Cell, EmptyState, ErrorState, Field, Loading, PageHeader, Pagination, Select, Table } from '../ui'
import { humanize, vehicleStatusTone } from '../ui/format'

const TYPES: VehicleType[] = ['BIKE', 'VAN', 'TRUCK']

export function VehiclesPage() {
  const [type, setType] = useState<VehicleType | ''>('')
  const [page, setPage] = useState(0)

  const list = useQuery({
    queryKey: ['vehicles', type, page],
    queryFn: () => fleet.vehicles({ type, page, size: 20 }),
  })

  return (
    <>
      <PageHeader
        title="Vehicles"
        description="The fleet and what each vehicle can carry. Capacity here is what the assignment engine enforces."
      />
      <Card>
        <div className="mb-4 flex flex-wrap items-end gap-3">
          <Field label="Type" htmlFor="vehicle-type">
            <Select
              id="vehicle-type"
              value={type}
              onChange={(e) => {
                setType(e.target.value as VehicleType | '')
                setPage(0)
              }}
            >
              <option value="">Any</option>
              {TYPES.map((value) => (
                <option key={value} value={value}>
                  {humanize(value)}
                </option>
              ))}
            </Select>
          </Field>
        </div>

        {list.isPending ? (
          <Loading label="Loading vehicles" />
        ) : list.error ? (
          <ErrorState error={list.error} onRetry={() => void list.refetch()} />
        ) : list.data.content.length === 0 ? (
          <EmptyState title="No vehicles match this filter" />
        ) : (
          <>
            <Table head={['Plate', 'Type', 'Max weight', 'Max volume', 'Status']}>
              {list.data.content.map((vehicle) => (
                <tr key={vehicle.id}>
                  <Cell className="font-medium">{vehicle.plateNumber}</Cell>
                  <Cell>{humanize(vehicle.type)}</Cell>
                  <Cell>{vehicle.maxWeightKg} kg</Cell>
                  <Cell>{vehicle.maxVolumeM3} m³</Cell>
                  <Cell>
                    <Badge tone={vehicleStatusTone(vehicle.status)}>{humanize(vehicle.status)}</Badge>
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
    </>
  )
}
