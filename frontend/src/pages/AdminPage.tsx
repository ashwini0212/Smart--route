import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { admin, assignment, events, tracking } from '../api/endpoints'
import type { AssignmentSettings, Role } from '../api/types'
import { Badge, Button, Card, Caveat, Cell, ErrorState, Field, Input, Loading, PageHeader, Select, Table } from '../ui'
import { dateTime, humanize } from '../ui/format'

const ROLES: Role[] = ['ADMIN', 'DISPATCHER', 'DRIVER', 'VIEWER']

/**
 * Administration: the knobs that would otherwise need a developer.
 *
 * The assignment weights are the interesting one — they change how every future assignment is ranked, and the
 * page says what that means rather than presenting three numbers without context.
 */
export function AdminPage() {
  const queryClient = useQueryClient()
  const [note, setNote] = useState<string | null>(null)

  const settings = useQuery({ queryKey: ['assignment-config'], queryFn: () => assignment.settings() })
  const users = useQuery({ queryKey: ['users'], queryFn: () => admin.users({ size: 50 }) })
  const outbox = useQuery({ queryKey: ['outbox'], queryFn: () => events.outbox(), refetchInterval: 15_000 })

  const save = useMutation({
    mutationFn: (body: Omit<AssignmentSettings, 'updatedAt'>) => assignment.updateSettings(body),
    onSuccess: (saved) => {
      setNote(`Weights saved. New assignments are ranked with these from now on (updated ${dateTime(saved.updatedAt)}).`)
      void queryClient.invalidateQueries({ queryKey: ['assignment-config'] })
    },
  })
  const setRole = useMutation({
    mutationFn: ({ id, role }: { id: number; role: Role }) => admin.setRole(id, role),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['users'] }),
  })
  const setEnabled = useMutation({
    mutationFn: ({ id, enabled }: { id: number; enabled: boolean }) => admin.setEnabled(id, enabled),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['users'] }),
  })
  const sweep = useMutation({
    mutationFn: () => tracking.sweep(),
    onSuccess: (result) =>
      setNote(
        `Checked ${result.driversChecked} drivers in ${result.durationMillis} ms: ${result.delaysReported} delay alerts, ` +
          `${result.routesRecalculated} routes changed, ${result.failures} skipped.`,
      ),
  })
  const rebuild = useMutation({
    mutationFn: () => tracking.rebuild(),
    onSuccess: (result) => setNote(`Rebuilt the live position cache from the database: ${result.positions} positions.`),
  })

  return (
    <>
      <PageHeader title="Admin" description="Users, assignment weights, and the two tracking jobs you can run by hand." />

      {note && <p className="mb-4 rounded-md bg-sky-50 px-3 py-2 text-sm text-sky-900 ring-1 ring-sky-200 ring-inset">{note}</p>}
      {(save.error || setRole.error || setEnabled.error || sweep.error || rebuild.error) && (
        <div className="mb-4">
          <ErrorState error={save.error ?? setRole.error ?? setEnabled.error ?? sweep.error ?? rebuild.error} />
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-2">
        <Card title="Assignment weights">
          {settings.isPending ? (
            <Loading />
          ) : settings.error ? (
            <ErrorState error={settings.error} onRetry={() => void settings.refetch()} />
          ) : (
            <form
              className="space-y-4"
              onSubmit={(event) => {
                event.preventDefault()
                const form = new FormData(event.currentTarget)
                const number = (name: string) => Number(form.get(name))
                save.mutate({
                  etaWeight: number('etaWeight'),
                  workloadWeight: number('workloadWeight'),
                  capacityWeight: number('capacityWeight'),
                  etaCapSeconds: number('etaCapSeconds'),
                  searchRadiusMeters: number('searchRadiusMeters'),
                  maxCandidates: number('maxCandidates'),
                  maxActiveDeliveries: number('maxActiveDeliveries'),
                })
              }}
            >
              <div className="grid grid-cols-3 gap-3">
                <Field label="ETA weight" htmlFor="etaWeight">
                  <Input id="etaWeight" name="etaWeight" defaultValue={settings.data.etaWeight} inputMode="decimal" />
                </Field>
                <Field label="Workload weight" htmlFor="workloadWeight">
                  <Input id="workloadWeight" name="workloadWeight" defaultValue={settings.data.workloadWeight} inputMode="decimal" />
                </Field>
                <Field label="Capacity weight" htmlFor="capacityWeight">
                  <Input id="capacityWeight" name="capacityWeight" defaultValue={settings.data.capacityWeight} inputMode="decimal" />
                </Field>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <Field label="ETA cap (seconds)" htmlFor="etaCapSeconds" hint="An ETA at or above this scores zero">
                  <Input id="etaCapSeconds" name="etaCapSeconds" defaultValue={settings.data.etaCapSeconds} inputMode="numeric" />
                </Field>
                <Field label="Search radius (m)" htmlFor="searchRadiusMeters">
                  <Input id="searchRadiusMeters" name="searchRadiusMeters" defaultValue={settings.data.searchRadiusMeters} inputMode="numeric" />
                </Field>
                <Field label="Max candidates" htmlFor="maxCandidates">
                  <Input id="maxCandidates" name="maxCandidates" defaultValue={settings.data.maxCandidates} inputMode="numeric" />
                </Field>
                <Field label="Max active per driver" htmlFor="maxActiveDeliveries">
                  <Input id="maxActiveDeliveries" name="maxActiveDeliveries" defaultValue={settings.data.maxActiveDeliveries} inputMode="numeric" />
                </Field>
              </div>
              <Caveat>
                These weights decide how candidate drivers are ranked. Raising the ETA weight sends orders to the
                nearest driver and concentrates work; raising the workload weight spreads it but adds driving. The
                project measured both: see the Phase 7 notes.
              </Caveat>
              <div className="flex items-center gap-3">
                <Button type="submit" loading={save.isPending}>
                  Save weights
                </Button>
                <span className="text-xs text-slate-500">Last changed {dateTime(settings.data.updatedAt)}</span>
              </div>
            </form>
          )}
        </Card>

        <Card title="Tracking jobs">
          <div className="space-y-3 text-sm">
            <p className="text-slate-600">
              Both of these run on their own: the delay and recalculation sweep every 15 seconds and immediately after
              a traffic change, and the position cache is filled by the event stream. These buttons are for when you
              want an answer now.
            </p>
            <div className="flex flex-wrap gap-2">
              <Button variant="secondary" loading={sweep.isPending} onClick={() => sweep.mutate()}>
                Run the delay sweep
              </Button>
              <Button variant="secondary" loading={rebuild.isPending} onClick={() => rebuild.mutate()}>
                Rebuild position cache
              </Button>
            </div>
            {outbox.data && (
              <p className="text-xs text-slate-500">
                Outbox: {outbox.data.pending.toLocaleString()} pending, {outbox.data.published.toLocaleString()}{' '}
                published.{' '}
                {outbox.data.pending > 100
                  ? 'A pending count this high means the relay is behind.'
                  : 'The relay is keeping up.'}
              </p>
            )}
          </div>
        </Card>
      </div>

      <div className="mt-6">
        <Card title="Users">
          {users.isPending ? (
            <Loading />
          ) : users.error ? (
            <ErrorState error={users.error} onRetry={() => void users.refetch()} />
          ) : (
            <Table head={['Email', 'Name', 'Role', 'Enabled', 'Last sign-in', '']}>
              {users.data.content.map((user) => (
                <tr key={user.id}>
                  <Cell className="font-medium">{user.email}</Cell>
                  <Cell>{user.fullName}</Cell>
                  <Cell>
                    <Select
                      aria-label={`Role of ${user.email}`}
                      value={user.role}
                      onChange={(e) => setRole.mutate({ id: user.id, role: e.target.value as Role })}
                    >
                      {ROLES.map((role) => (
                        <option key={role} value={role}>
                          {humanize(role)}
                        </option>
                      ))}
                    </Select>
                  </Cell>
                  <Cell>
                    <Badge tone={user.enabled ? 'success' : 'danger'}>{user.enabled ? 'enabled' : 'disabled'}</Badge>
                  </Cell>
                  <Cell className="text-xs">{dateTime(user.lastLoginAt)}</Cell>
                  <Cell>
                    <Button
                      variant={user.enabled ? 'danger' : 'secondary'}
                      onClick={() => setEnabled.mutate({ id: user.id, enabled: !user.enabled })}
                    >
                      {user.enabled ? 'Disable' : 'Enable'}
                    </Button>
                  </Cell>
                </tr>
              ))}
            </Table>
          )}
          <p className="mt-3 text-xs text-slate-500">
            A disabled account cannot sign in, and its refresh tokens stop working on the next attempt. Roles are
            enforced on the server for every request, so changing one here changes what that account can actually do.
          </p>
        </Card>
      </div>
    </>
  )
}
