# Phase 11: the dispatcher dashboard

## STEP 1: What we built

![The dashboard](../images/phase-11-dashboard.png)

| Piece | Purpose |
|---|---|
| `api/client.ts` | One fetch wrapper: the access token, the single retry after a refresh, and `ApiError` |
| `api/endpoints.ts` | One function per endpoint, so a page never builds a URL |
| `api/types.ts` | The backend's records written out as TypeScript |
| `api/sse.ts` | A server-sent event reader built on `fetch`, because `EventSource` cannot send an Authorization header |
| `hooks/useLiveStream.ts` | The live subscription and `applyFrame`, the pure function that folds a frame into state |
| `ui/` | The design system: button, field, table, badge, pagination, the three list states, the caveat note |
| `auth/` | Who is signed in, restored on load from the refresh cookie |
| `app/` | The route table, the layout, and the role guard |
| 11 pages | Login, Dashboard, Live map, Orders, Order detail, Drivers, Vehicles, Route planner, My deliveries, Events, Admin |

The eleventh page in the plan is Analytics. It is **not** here: the endpoints behind it are Phase 12, and a page of made-up charts is exactly what this project is not for. `/analytics` currently redirects to the dashboard.

## STEP 2: Architecture

```
main.tsx
 ├─ QueryClientProvider     server state: caching, refetch intervals, loading and error states
 ├─ AuthProvider            who is signed in; one refresh on load restores a session
 └─ BrowserRouter → App     the route table, with a role guard per route

a page
 ├─ useQuery(endpoints.x)  ──► api/client.ts ──► fetch ──► /api/...
 │                                  │ 401 once → POST /api/auth/refresh → retry
 └─ useLiveStream()        ──► api/sse.ts   ──► fetch /api/tracking/stream (held open)
                                    └─ applyFrame(state, event, payload)
```

Two kinds of state, kept apart on purpose:
- **Server state** (orders, drivers, routes) belongs to TanStack Query: it is a cache of something the server owns, with staleness, refetching and errors handled in one place.
- **Live state** (positions, alerts) belongs to `useLiveStream`: it arrives unasked, is folded into a `Map` and some capped lists, and is never written back.

Nothing is kept in a global store beyond those two, because nothing else needed to be shared.

## STEP 3: Design decisions explained

### 3.1 The access token lives in memory
A token in `localStorage` is readable by any script that ends up on the page and survives the tab. This one is a module variable: it dies with the tab, and a reload recovers the session with one call to `/api/auth/refresh`, whose cookie is `HttpOnly` and therefore not readable by JavaScript at all. The cost is a refresh round trip on every page load, and a token lost on a hard refresh of a very slow network.

### 3.2 One refresh, shared
Five queries mount at once; if the token has expired they all get a 401 together. Refreshing five times would rotate the refresh token five times, and the backend treats a reused refresh token as theft and revokes the whole family (Phase 5). So `refreshAccessToken` keeps the in-flight promise and hands the same one to every caller, and a request is retried exactly once.

### 3.3 Roles decide what is shown, never what is allowed
The navigation hides pages a role cannot use and `RequireAuth` explains rather than redirecting when the role is wrong — but every endpoint behind them re-checks the role on the server from the signed token. Editing `user.role` in a debugger changes the menu and nothing else. This is the client half of "never trust role information from the frontend": the client's copy exists so the UI is not a wall of 403s.

### 3.4 The stream is read with `fetch`, not `EventSource`
`EventSource` cannot send headers, so with a token in memory it cannot authenticate. The alternatives were a token in the query string — which lands in access logs and browser history — or accepting the refresh cookie on that one endpoint, making it the only endpoint any site could call on the user's behalf. Reading the body with `fetch` costs `api/sse.ts` (frame parsing) and the reconnection `EventSource` would have given for free, which is why `useLiveStream` backs off: 1 s, 2 s, 4 s, up to 30 s. The server drops clients it cannot write to, so a client that reconnected in a tight loop would be the reason it never catches up.

### 3.5 Nothing is interpolated on the map
A driver marker moves only when the server reports a position. Interpolating between reports would look smoother and would be a guess the user cannot tell from a measurement — on this map, a marker that has not moved for ten seconds means *no report for ten seconds*, which is information. For the same reason a position from the stream replaces one from the initial load only if its timestamp is newer, and a simulated position is drawn in a different colour as well as labelled.

### 3.6 Every heuristic says so, in the place it is read
The candidate list shows the score broken into its parts and names the algorithm; the stop order says "proven shortest order" or "heuristic order" with the measured 2 % average gap; auto-dispatch reports what it did and adds "not a proven best allocation"; the simulation badge sits in the header whenever anything is simulated. These are one-line additions that took the longest to word, and they are the difference between a dashboard and a demo.

### 3.7 Three list states, written once
`Loading`, `EmptyState` and `ErrorState` live in the design system, so no page can forget one. The error state shows the server's own message (the backend never sends a stack trace) and the trace id — but only for a 5xx, because on a 404 the trace id is noise in front of a message that already explains itself. That last part came from watching the live map show a trace id for "this driver has no active deliveries".

### 3.8 Leaflet is loaded only by the pages that use it
The map and the planner are `React.lazy`, which moves Leaflet (153 kB) and its CSS out of the first load. A dispatcher who lives on the Orders page never downloads it. The main bundle is 352 kB (108 kB gzipped), which is honest rather than good; cutting it further would mean splitting TanStack Query and the router too, and that is not worth doing before anyone has measured a slow load.

## STEP 4: Contracts

The frontend is a client, so its contract is the backend's. Two notes on the parts that are not obvious from the endpoint list:

- **The SSE frames.** `driver-moved` carries a position (`driverId, latitude, longitude, at, source`). Every other frame (`order-status`, `delivery-delayed`, `route-recalculated`) carries the event envelope: `{eventId, type, aggregateType, aggregateId, occurredAt, payload}`. `hello` and `heartbeat` carry text.
- **Dev and production routing.** In development Vite proxies `/api` and `/actuator` to `http://localhost:8080`, so the browser sees one origin and CORS is not involved. In Docker nginx does the same. The backend's CORS allow-list (`CORS_ALLOWED_ORIGINS`, default `http://localhost:5173`) only matters if the frontend is served from a different origin — which is how this phase's first browser run failed, with a 403 on login from port 5174.

## STEP 5: Tests (56 frontend, 51 new)
Testing Library with jsdom, driving the components through the DOM the way a user would, with `fetch` stubbed per endpoint.

- `api/client.test.ts` (7): sends the token; refreshes once after a 401 and retries with the new token; reports the session lost when the refresh fails; **two simultaneous 401s share one refresh** (a second rotation would revoke the token family); turns an error body into field errors and a trace id; falls back to the status for a non-JSON body; leaves empty filters out of the query string.
- `api/sse.test.ts` (7): reads an event and its data; keeps a half-received frame for the next chunk; several frames in one chunk; multi-line data and comments; the default event name; reads frames off a real `ReadableStream` and sends the Authorization header; rejects when the server refuses.
- `hooks/useLiveStream.test.ts` (8): records a position; **ignores one older than the one on screen**; accepts a newer one; one alert per order, newest first, updated in place; the lists are capped at 40; the greeting and heartbeat mean the stream is open; an unknown event is counted and otherwise ignored; a malformed frame changes nothing.
- `app/RequireAuth.test.tsx` (5): waits while the session is being restored; redirects a signed-out visitor; lets a signed-in user through; explains instead of redirecting when the role is wrong; allows the right role.
- `pages/OrdersPage.test.tsx` (8): loading, listed rows, empty, and the server's message with a retry on failure; **a filter change asks the server rather than filtering the page already loaded**; creation and dispatch are offered to staff only; auto-dispatch reports its result without calling it optimal; a rejected order shows the server's field error.
- `pages/OrderDetailPage.test.tsx` (6): order and history; candidates are ranked only when asked, with the score breakdown and the algorithm; exclusion counts when nobody is eligible; assigning posts the right body and the order reloads; a 409 shows the server's message; a viewer gets no assignment controls.
- `pages/DeliveriesPage.test.tsx` (5): the driver's deliveries and the suggested order, labelled as not proven best; only the transitions the server accepts are offered; **a status report carries no driver id** (the server takes it from the token); nothing assigned is an empty state and the route is not requested; a stop the server predicts late is marked.
- `ui/format.test.ts` (5) and `components/SystemStatusCard.test.tsx` (5, from Phase 1).

## STEP 6: Checked against the running stack
The suite uses stubs, so the dashboard was also driven in a real browser (headless Chromium, Playwright) against `docker compose` with both simulators on, signed in as the seeded dispatcher and admin accounts:

| Page | What was observed |
|---|---|
| Dashboard | 663 waiting, 139 assigned, 39 available, "SIMULATION: driver movement and traffic" in the header |
| Live map | 124 driver markers, "stream open", 155 frames in the first few seconds |
| Orders | 20 rows, filters and paging |
| Route planner | A* route 13.2 km / 20 min, `optimal`, 2,550 nodes settled; Held-Karp ordered 3 stops and said so |
| Events | the recorded stream with payloads, outbox 7 pending / 245,114 published (an exact count then; the field became `publishedEstimate` in Phase 13) |
| Admin | the sweep ran on demand: 48 drivers in 1,305 ms, 1 delay alert |

Two things this found, both fixed above: the 403 from the CORS allow-list when the dev server came up on a different port, and the trace id shown for an ordinary 404.

**One honest caveat:** the map tiles come from `tile.openstreetmap.org`, which this sandbox's proxy blocks, so the browser run showed markers on an empty canvas. Markers, popups, the route polyline and the fit-to-bounds all work; the basemap needs outbound access to the tile server. And the routing graph is the synthetic city by default, so routes will not follow the streets a real basemap would draw — the map says so on the page.

## STEP 7: Review notes
- **The CORS failure was a real one.** Running the dev server on port 5174 produced a 403 on login that said nothing useful in the browser. It is the system working — the allow-list is `http://localhost:5173` — but it is worth knowing that the symptom of a wrong origin here is a 403 on the login call, not a CORS message.
- **`oxlint` caught a mixed module.** `AuthContext.tsx` exported the provider, the hook and a helper, which breaks Vite's fast refresh. Split into `auth/context.ts` (context and `hasRole`), `auth/useAuth.ts` and `auth/AuthProvider.tsx`.
- **A `useState` that should have been a ref.** The map's fit-to-bounds stored "have I fitted yet" in state, which re-rendered for nothing. It is a ref now; the lint rule was right.
- **Two tests found their own bugs**: the "Delivered" badge assertion matched the filter dropdown as well (now scoped to the table), and asserting the body of the delivery status call is what keeps the driver id out of it.
- Deliberate gaps: no Analytics page (Phase 12), no optimistic updates anywhere (a dispatcher seeing an assignment that later fails is worse than waiting 200 ms), and no virtualized tables — the pages are server-paginated at 20 rows.

## Interview questions
1. The access token is in a module variable and the refresh token in an HttpOnly cookie. What attack does each half of that defend against?
2. Five requests get a 401 at the same moment. What goes wrong if each one refreshes, and how does the client avoid it?
3. The navigation hides the Admin page from a dispatcher. Why is that not a security control, and what is?
4. Why can `EventSource` not be used here, and what did reading the stream with `fetch` cost?
5. The server already discards out-of-order positions. Why does the client compare timestamps too?
6. What would be wrong with animating a driver marker smoothly between two reported positions?
7. Server state and live state are handled by two different mechanisms in this app. What distinguishes them?
8. Where does this UI say that something is a heuristic, and why in those places rather than in a help page?
9. A trace id is shown for a 500 but not for a 404. Make the argument for that, and against it.
10. Leaflet is lazily loaded but TanStack Query is not. How would you decide whether that is worth changing?
11. The browser run failed with a 403 on login. What was actually wrong, and why did it look like an authentication problem?
12. The Analytics page was deliberately not built in this phase. Why, and what would building it early have cost?
