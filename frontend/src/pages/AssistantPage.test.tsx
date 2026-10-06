import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { AssistantPage } from './AssistantPage'
import type { AssistantAnswer } from '../api/types'
import { json, renderWithProviders, stubFetch } from '../test/render'

const ON = {
  enabled: true,
  reason: '',
  model: 'claude-opus-5-5',
  tools: ['analytics_overview', 'find_orders', 'fleet_positions', 'list_warehouses', 'order_detail',
    'rank_drivers_for_order'],
}

function answer(overrides: Partial<AssistantAnswer> = {}): AssistantAnswer {
  return {
    facts: ['12 orders are in CREATED, which means waiting for a driver.'],
    recommendations: ['Dispatch the two URGENT ones first.'],
    uncertainty: ['Driver positions are simulated, so none of this is a real sighting.'],
    text: 'FACTS\n- ...',
    sectionsParsed: true,
    toolCalls: [{ tool: 'find_orders', arguments: '{"status":"CREATED"}', failed: false, millis: 14 }],
    toolRounds: 1,
    toolLimitReached: false,
    model: 'claude-opus-5-5',
    usage: { inputTokens: 2400, outputTokens: 310, cacheReadTokens: 0, requests: 2 },
    ...overrides,
  }
}

describe('Assistant page', () => {
  it('says what is missing instead of offering a box that cannot work', async () => {
    stubFetch({
      '/api/assistant/status': () =>
        json({ enabled: false, reason: 'The assistant is turned off (set ASSISTANT_ENABLED=true).', model: 'claude-opus-5-5', tools: [] }),
    })
    renderWithProviders(<AssistantPage />)

    expect(await screen.findByText(/set ASSISTANT_ENABLED=true/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Ask' })).not.toBeInTheDocument()
  })

  it('keeps facts, recommendations and uncertainty in separate blocks, and lists the tool calls', async () => {
    const calls = stubFetch({
      '/api/assistant/status': () => json(ON),
      '/api/assistant/ask': () => json(answer()),
    })
    renderWithProviders(<AssistantPage />)

    await userEvent.type(await screen.findByLabelText('Question'), 'What is waiting?')
    await userEvent.click(screen.getByRole('button', { name: 'Ask' }))

    expect(await screen.findByText(/12 orders are in CREATED/)).toBeInTheDocument()
    // The headings are what stop a suggestion being read as a measurement.
    expect(screen.getByText('Facts')).toBeInTheDocument()
    expect(screen.getByText("The model's suggestions. Not measurements.")).toBeInTheDocument()
    expect(screen.getByText(/Driver positions are simulated/)).toBeInTheDocument()
    expect(screen.getByText('find_orders')).toBeInTheDocument()
    expect(screen.getByText('{"status":"CREATED"}')).toBeInTheDocument()
    expect(screen.getByText(/1 tool round, 2 model requests/)).toBeInTheDocument()

    const asked = calls.find((call) => call.url === '/api/assistant/ask')
    expect(asked?.init?.body).toBe(JSON.stringify({ question: 'What is waiting?' }))
  })

  it('shows the answer as written when it did not use the three sections', async () => {
    stubFetch({
      '/api/assistant/status': () => json(ON),
      '/api/assistant/ask': () =>
        json(answer({
          sectionsParsed: false,
          facts: [],
          recommendations: [],
          uncertainty: [],
          text: 'There are twelve orders waiting and I would start with the urgent ones.',
        })),
    })
    renderWithProviders(<AssistantPage />)

    await userEvent.click(await screen.findByRole('button', { name: /How many orders are waiting/ }))

    expect(await screen.findByText(/There are twelve orders waiting/)).toBeInTheDocument()
    expect(screen.getByText(/shown as written/)).toBeInTheDocument()
    expect(screen.queryByText('Facts')).not.toBeInTheDocument()
  })

  it('warns when the answer stopped at the tool limit', async () => {
    stubFetch({
      '/api/assistant/status': () => json(ON),
      '/api/assistant/ask': () => json(answer({ toolRounds: 6, toolLimitReached: true })),
    })
    renderWithProviders(<AssistantPage />)

    await userEvent.click(await screen.findByRole('button', { name: /Why does the oldest waiting order/ }))

    await waitFor(() =>
      expect(screen.getByText(/reached its tool-call limit/)).toBeInTheDocument())
  })

  it('does not ask on an empty question', async () => {
    const calls = stubFetch({ '/api/assistant/status': () => json(ON) })
    renderWithProviders(<AssistantPage />)

    await userEvent.click(await screen.findByRole('button', { name: 'Ask' }))

    expect(calls.some((call) => call.url === '/api/assistant/ask')).toBe(false)
  })
})
