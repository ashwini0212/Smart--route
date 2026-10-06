import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { assistant } from '../api/endpoints'
import type { AssistantAnswer } from '../api/types'
import { Badge, Button, Card, Caveat, EmptyState, ErrorState, Field, Loading, PageHeader } from '../ui'

const EXAMPLES = [
  'How many orders are waiting for a driver, and which has waited longest?',
  'Why does the oldest waiting order have no driver?',
  'How did deliveries go over the last 7 days?',
]

/**
 * The assistant (FR-24): optional, read-only, and honest about which parts of its answer are measurements.
 *
 * Three decisions shape this page. It asks the server whether the feature is configured before offering
 * anything, because an input box that can only produce a 503 is worse than no input box. It renders FACTS,
 * RECOMMENDATIONS and UNCERTAINTY as three separate blocks, so the measured part and the suggested part cannot
 * be read as one paragraph. And it lists the tool calls the answer came from, which is the only way a
 * dispatcher can check a claim without trusting the model.
 */
export function AssistantPage() {
  const [question, setQuestion] = useState('')
  const [answer, setAnswer] = useState<AssistantAnswer | null>(null)

  const status = useQuery({ queryKey: ['assistant', 'status'], queryFn: () => assistant.status() })
  const ask = useMutation({
    mutationFn: (text: string) => assistant.ask(text),
    onSuccess: setAnswer,
  })

  const submit = (text: string) => {
    const trimmed = text.trim()
    if (!trimmed) return
    setQuestion(trimmed)
    ask.mutate(trimmed)
  }

  return (
    <>
      <PageHeader
        title="Assistant"
        description="Answers questions about current operations by calling the same read-only APIs this dashboard uses. It cannot change anything."
      />

      {status.isLoading && <Loading label="Checking whether the assistant is configured" />}
      {status.error && <ErrorState error={status.error} onRetry={() => status.refetch()} />}

      {status.data && !status.data.enabled && (
        <Card title="Not configured">
          <p className="text-sm text-slate-600">{status.data.reason}</p>
          <p className="mt-2 text-sm text-slate-600">
            The assistant is the one optional part of SmartRoute. Everything else works without it, which is why
            this page tells you what is missing instead of hiding.
          </p>
        </Card>
      )}

      {status.data?.enabled && (
        <>
          <Card title="Ask a question">
            <Field label="Question" htmlFor="question">
              <textarea
                id="question"
                rows={3}
                value={question}
                onChange={(e) => setQuestion(e.target.value)}
                maxLength={1000}
                placeholder="What should I look at first?"
                className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm shadow-sm focus:border-slate-500 focus:outline-none"
              />
            </Field>
            <div className="mt-3 flex flex-wrap items-center gap-2">
              <Button loading={ask.isPending} onClick={() => submit(question)}>
                Ask
              </Button>
              {EXAMPLES.map((example) => (
                <Button key={example} variant="secondary" onClick={() => submit(example)}>
                  {example.length > 42 ? `${example.slice(0, 42)}…` : example}
                </Button>
              ))}
            </div>
            <Caveat>
              {status.data.model} answers by calling {status.data.tools.length} read-only tools:{' '}
              {status.data.tools.join(', ')}. It has no tool that writes, so it can describe what to do but
              never do it.
            </Caveat>
          </Card>

          {ask.isPending && <Loading label="Asking, and running the tools it asks for" />}
          {ask.error && <ErrorState error={ask.error} />}
          {answer && <AnswerCard answer={answer} />}
          {!answer && !ask.isPending && !ask.error && (
            <EmptyState title="No question asked yet" hint="Pick one of the examples to see what it does." />
          )}
        </>
      )}
    </>
  )
}

function AnswerCard({ answer }: { answer: AssistantAnswer }) {
  return (
    <>
      {!answer.sectionsParsed ? (
        <Card title="Answer">
          {/* The model did not use the three sections. Showing its text verbatim beats showing a parse of it. */}
          <p className="whitespace-pre-wrap text-sm text-slate-700">{answer.text}</p>
          <Caveat>
            This answer did not arrive in the three sections the assistant is asked for, so it is shown as
            written. Treat it as one block rather than as separated facts and suggestions.
          </Caveat>
        </Card>
      ) : (
        <>
          <Section title="Facts" tone="neutral" hint="From tool calls, listed below." items={answer.facts} />
          <Section
            title="Recommendations"
            tone="warning"
            hint="The model's suggestions. Not measurements."
            items={answer.recommendations}
          />
          <Section
            title="Uncertainty"
            tone="neutral"
            hint="What it could not check."
            items={answer.uncertainty}
          />
        </>
      )}

      <Card title="Where this came from">
        {answer.toolCalls.length === 0 ? (
          <p className="text-sm text-slate-600">
            It called no tools, so nothing here is grounded in current data. Treat the answer as general.
          </p>
        ) : (
          <ul className="space-y-1 text-sm text-slate-700">
            {answer.toolCalls.map((call, index) => (
              <li key={`${call.tool}-${index}`} className="flex flex-wrap items-center gap-2">
                <code className="rounded bg-slate-100 px-1.5 py-0.5 text-xs">{call.tool}</code>
                <span className="text-xs text-slate-500">{call.arguments}</span>
                {call.failed && <Badge tone="danger">rejected</Badge>}
                <span className="text-xs text-slate-400">{call.millis} ms</span>
              </li>
            ))}
          </ul>
        )}
        <Caveat>
          {answer.toolRounds} tool {answer.toolRounds === 1 ? 'round' : 'rounds'}, {answer.usage.requests}{' '}
          model {answer.usage.requests === 1 ? 'request' : 'requests'}, {answer.usage.inputTokens} input and{' '}
          {answer.usage.outputTokens} output tokens.
          {answer.toolLimitReached &&
            ' It reached its tool-call limit and was told to answer with what it had, so it may not have checked everything.'}
        </Caveat>
      </Card>
    </>
  )
}

function Section({
  title,
  hint,
  items,
  tone,
}: {
  title: string
  hint: string
  items: string[]
  tone: 'neutral' | 'warning'
}) {
  return (
    <Card title={title} actions={<Badge tone={tone === 'warning' ? 'warning' : 'neutral'}>{hint}</Badge>}>
      {items.length === 0 ? (
        <p className="text-sm text-slate-500">Nothing under this heading.</p>
      ) : (
        <ul className="list-disc space-y-1 pl-5 text-sm text-slate-700">
          {items.map((item, index) => (
            <li key={`${title}-${index}`}>{item}</li>
          ))}
        </ul>
      )}
    </Card>
  )
}
