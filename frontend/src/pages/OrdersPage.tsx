import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../api/client'
import { assignment, orders, warehouses } from '../api/endpoints'
import type { CreateOrderRequest, OrderSearchParams } from '../api/endpoints'
import type { OrderPriority, OrderStatus, VehicleType } from '../api/types'
import { hasRole } from '../auth/context'
import { useAuth } from '../auth/useAuth'
import { useDebouncedValue } from '../hooks/useDebouncedValue'
import { Badge, Button, Card, Cell, EmptyState, ErrorState, Field, Input, Loading, Modal, PageHeader, Pagination, Select, Table } from '../ui'
import { dateTime, humanize, orderStatusTone, priorityTone } from '../ui/format'

const STATUSES: OrderStatus[] = ['CREATED', 'ASSIGNED', 'PICKED_UP', 'IN_TRANSIT', 'DELIVERED', 'FAILED', 'CANCELLED']
const PRIORITIES: OrderPriority[] = ['LOW', 'NORMAL', 'HIGH', 'URGENT']
const VEHICLES: VehicleType[] = ['BIKE', 'VAN', 'TRUCK']

export function OrdersPage() {
  const { user } = useAuth()
  const staff = hasRole(user, 'ADMIN', 'DISPATCHER')
  const [filters, setFilters] = useState<OrderSearchParams>({ status: '', priority: '', page: 0, size: 20 })
  const [creating, setCreating] = useState(false)
  const [dispatchResult, setDispatchResult] = useState<string | null>(null)
  const queryClient = useQueryClient()

  // The driver-id box is typed into, and it is part of the query key, so the key uses a debounced copy: four
  // keystrokes used to mean four server-side filtered queries. The input stays bound to the raw state.
  const driverId = useDebouncedValue(filters.driverId ?? '')
  const query = useMemo(() => ({ ...filters, driverId }), [filters, driverId])

  const list = useQuery({
    queryKey: ['orders', query],
    queryFn: () => orders.search(query),
  })

  const autoDispatch = useMutation({
    mutationFn: () => assignment.auto(50),
    onSuccess: (result) => {
      // The wording matters: this is a greedy pass, not an optimum, and the server says so in `algorithm`.
      setDispatchResult(
        `Assigned ${result.assignedCount} of ${result.waitingAtStart} waiting orders in ${result.durationMillis} ms; ` +
          `${result.notAssignedCount} could not be assigned, ${result.stillWaiting} still waiting.`,
      )
      void queryClient.invalidateQueries({ queryKey: ['orders'] })
    },
  })

  const update = (next: Partial<OrderSearchParams>) => setFilters((current) => ({ ...current, page: 0, ...next }))

  return (
    <>
      <PageHeader
        title="Orders"
        description="Every delivery order, newest first. Filters are applied by the server, so paging is over the filtered set."
        actions={
          staff && (
            <div className="flex gap-2">
              <Button variant="secondary" loading={autoDispatch.isPending} onClick={() => autoDispatch.mutate()}>
                Auto-dispatch waiting
              </Button>
              <Button onClick={() => setCreating(true)}>New order</Button>
            </div>
          )
        }
      />

      {autoDispatch.error && <div className="mb-4"><ErrorState error={autoDispatch.error} /></div>}
      {dispatchResult && (
        <p className="mb-4 rounded-md bg-sky-50 px-3 py-2 text-sm text-sky-900 ring-1 ring-sky-200 ring-inset">
          {dispatchResult} Greedy assignment, most urgent first — not a proven best allocation.
        </p>
      )}

      <Card>
        <div className="mb-4 flex flex-wrap items-end gap-3">
          <Field label="Status" htmlFor="status">
            <Select
              id="status"
              value={filters.status ?? ''}
              onChange={(e) => update({ status: e.target.value as OrderStatus | '' })}
            >
              <option value="">Any</option>
              {STATUSES.map((status) => (
                <option key={status} value={status}>
                  {humanize(status)}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Priority" htmlFor="priority">
            <Select
              id="priority"
              value={filters.priority ?? ''}
              onChange={(e) => update({ priority: e.target.value as OrderPriority | '' })}
            >
              <option value="">Any</option>
              {PRIORITIES.map((priority) => (
                <option key={priority} value={priority}>
                  {humanize(priority)}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Driver id" htmlFor="driverId" hint="Leave empty for all drivers">
            <Input
              id="driverId"
              inputMode="numeric"
              value={filters.driverId ?? ''}
              onChange={(e) => update({ driverId: e.target.value === '' ? '' : Number(e.target.value) })}
            />
          </Field>
        </div>

        {list.isPending ? (
          <Loading label="Loading orders" />
        ) : list.error ? (
          <ErrorState error={list.error} onRetry={() => void list.refetch()} />
        ) : list.data.content.length === 0 ? (
          <EmptyState title="No orders match these filters" hint="Clear a filter, or create an order." />
        ) : (
          <>
            <Table head={['Order', 'Customer', 'Status', 'Priority', 'Driver', 'Created']}>
              {list.data.content.map((order) => (
                <tr key={order.id}>
                  <Cell>
                    <Link to={`/orders/${order.id}`} className="font-medium underline">
                      {order.code}
                    </Link>
                  </Cell>
                  <Cell>
                    <span className="block">{order.customerName}</span>
                    <span className="text-xs text-slate-500">{order.dropAddress}</span>
                  </Cell>
                  <Cell>
                    <Badge tone={orderStatusTone(order.status)}>{humanize(order.status)}</Badge>
                  </Cell>
                  <Cell>
                    <Badge tone={priorityTone(order.priority)}>{humanize(order.priority)}</Badge>
                  </Cell>
                  <Cell>{order.driverId ?? <span className="text-slate-400">unassigned</span>}</Cell>
                  <Cell className="text-xs">{dateTime(order.createdAt)}</Cell>
                </tr>
              ))}
            </Table>
            <Pagination
              page={list.data.page}
              totalPages={list.data.totalPages}
              totalElements={list.data.totalElements}
              onPage={(page) => setFilters((current) => ({ ...current, page }))}
            />
          </>
        )}
      </Card>

      {creating && <CreateOrderDialog onClose={() => setCreating(false)} />}
    </>
  )
}

function CreateOrderDialog({ onClose }: { onClose: () => void }) {
  const queryClient = useQueryClient()
  const hubs = useQuery({ queryKey: ['warehouses'], queryFn: () => warehouses.all() })
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [message, setMessage] = useState<string | null>(null)

  const create = useMutation({
    mutationFn: (body: CreateOrderRequest) => orders.create(body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['orders'] })
      onClose()
    },
    onError: (error) => {
      // Validation lives on the server; this only displays what it rejected, field by field.
      if (error instanceof ApiError) {
        setFieldErrors(error.byField())
        setMessage(error.fieldErrors.length > 0 ? null : error.message)
      } else {
        setMessage('Could not reach the server')
      }
    },
  })

  const submit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setFieldErrors({})
    setMessage(null)
    const form = new FormData(event.currentTarget)
    const text = (name: string) => String(form.get(name) ?? '').trim()
    create.mutate({
      warehouseId: Number(form.get('warehouseId')),
      customerName: text('customerName'),
      dropAddress: text('dropAddress'),
      dropLatitude: Number(form.get('dropLatitude')),
      dropLongitude: Number(form.get('dropLongitude')),
      priority: text('priority') as OrderPriority,
      weightKg: Number(form.get('weightKg')),
      volumeM3: Number(form.get('volumeM3')),
      requiredVehicleType: text('requiredVehicleType') === '' ? null : (text('requiredVehicleType') as VehicleType),
      windowEnd: text('windowEnd') === '' ? null : new Date(text('windowEnd')).toISOString(),
    })
  }

  return (
    <Modal title="New order" onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <Field label="Pickup warehouse" htmlFor="warehouseId" error={fieldErrors.warehouseId}>
          <Select id="warehouseId" name="warehouseId" required defaultValue="">
            <option value="" disabled>
              {hubs.isPending ? 'Loading…' : 'Choose a warehouse'}
            </option>
            {hubs.data?.map((hub) => (
              <option key={hub.id} value={hub.id}>
                {hub.code} — {hub.name}
              </option>
            ))}
          </Select>
        </Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Customer name" htmlFor="customerName" error={fieldErrors.customerName}>
            <Input id="customerName" name="customerName" required maxLength={120} />
          </Field>
          <Field label="Drop address" htmlFor="dropAddress" error={fieldErrors.dropAddress}>
            <Input id="dropAddress" name="dropAddress" required maxLength={255} />
          </Field>
          <Field label="Drop latitude" htmlFor="dropLatitude" error={fieldErrors.dropLatitude}>
            <Input id="dropLatitude" name="dropLatitude" required inputMode="decimal" defaultValue="12.97" />
          </Field>
          <Field label="Drop longitude" htmlFor="dropLongitude" error={fieldErrors.dropLongitude}>
            <Input id="dropLongitude" name="dropLongitude" required inputMode="decimal" defaultValue="77.59" />
          </Field>
          <Field label="Priority" htmlFor="priority" error={fieldErrors.priority}>
            <Select id="priority" name="priority" defaultValue="NORMAL">
              {PRIORITIES.map((priority) => (
                <option key={priority} value={priority}>
                  {humanize(priority)}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Required vehicle" htmlFor="requiredVehicleType" hint="Optional">
            <Select id="requiredVehicleType" name="requiredVehicleType" defaultValue="">
              <option value="">Any</option>
              {VEHICLES.map((type) => (
                <option key={type} value={type}>
                  {humanize(type)}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Weight (kg)" htmlFor="weightKg" error={fieldErrors.weightKg}>
            <Input id="weightKg" name="weightKg" required inputMode="decimal" defaultValue="10" />
          </Field>
          <Field label="Volume (m³)" htmlFor="volumeM3" error={fieldErrors.volumeM3}>
            <Input id="volumeM3" name="volumeM3" required inputMode="decimal" defaultValue="0.5" />
          </Field>
          <Field label="Deliver by" htmlFor="windowEnd" hint="Optional; a missed window raises an alert" error={fieldErrors.windowEnd}>
            <Input id="windowEnd" name="windowEnd" type="datetime-local" />
          </Field>
        </div>
        {message && (
          <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-800 ring-1 ring-red-200 ring-inset">
            {message}
          </p>
        )}
        <div className="flex justify-end gap-2">
          <Button variant="secondary" type="button" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={create.isPending}>
            Create order
          </Button>
        </div>
      </form>
    </Modal>
  )
}
