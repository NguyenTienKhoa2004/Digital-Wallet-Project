import assert from 'node:assert/strict'
import { afterEach, test, mock } from 'node:test'
import { ApiError, createTransfer, getTransfer, getTransferByKey, getTransfers, searchRecipients } from '../src/api.ts'
import { centsFromInput, decimalAmount, money } from '../src/money.ts'
import { readPendingTransfer, resolveTransfer, walletIdPattern } from '../src/transfer-operation.ts'

const destinationWalletId = '72e3047e-d4a5-442e-92d5-564de8420b7c'
const sourceWalletId = '9ba10451-98c1-48b2-a4a5-b260fb7a861c'
const pending = { key: 'original-request-key', destinationWalletId, amount: '3050', description: ' Dinner 🍜 ' }
const transfer = {
  transactionId: 'transaction-id', reference: 'TRF-test', sourceWalletId, destinationWalletId,
  amount: '3050', currency: 'USD' as const, status: 'SUCCESS' as const,
  description: pending.description, createdAt: '2026-10-31T05:00:00Z', completedAt: '2026-10-31T05:00:01Z', failureCode: null,
}
afterEach(() => mock.restoreAll())

test('USD input keeps cents exact and accepts either decimal separator', () => {
  assert.equal(centsFromInput('30.50'), '3050')
  assert.equal(centsFromInput(' 30,5 '), '3050')
  assert.equal(centsFromInput('0.01'), '1')
  assert.equal(centsFromInput('90071992547409.93'), '9007199254740993')
  assert.equal(centsFromInput('92233720368547758.07'), '9223372036854775807')
  assert.equal(decimalAmount('9223372036854775807'), '92233720368547758.07')
  assert.equal(money('9223372036854775807'), '$92,233,720,368,547,758.07')
})

test('invalid, zero, negative, rounded and overflowing amounts are rejected', () => {
  for (const value of ['', '0', '0.00', '-1', '1.001', '1e3', '1,000.00', 'NaN', 'Infinity', '92233720368547758.08']) {
    assert.equal(centsFromInput(value), null, value)
  }
})

test('wallet IDs must be complete UUIDs', () => {
  assert.ok(walletIdPattern.test(destinationWalletId.toUpperCase()))
  assert.ok(!walletIdPattern.test('72e3047e'))
  assert.ok(!walletIdPattern.test('person@example.com'))
})

test('recovery preserves the exact key, amount, recipient and description', () => {
  assert.deepEqual(readPendingTransfer(JSON.stringify(pending)), pending)
  assert.deepEqual(readPendingTransfer(JSON.stringify({ ...pending, token: 'never-restore-this' })), pending)
  assert.equal(readPendingTransfer(JSON.stringify({ ...pending, amount: '9223372036854775807' }))?.amount, '9223372036854775807')
})

test('corrupted and out-of-range recovery records are rejected', () => {
  for (const raw of [null, '{', 'null', '[]', JSON.stringify({ ...pending, key: ' ' }), JSON.stringify({ ...pending, key: 'x'.repeat(256) }), JSON.stringify({ ...pending, amount: 3050 }), JSON.stringify({ ...pending, amount: '0' }), JSON.stringify({ ...pending, amount: '-1' }), JSON.stringify({ ...pending, amount: '9223372036854775808' }), JSON.stringify({ ...pending, destinationWalletId: 'bad-id' }), JSON.stringify({ ...pending, description: 'x'.repeat(256) })]) {
    assert.equal(readPendingTransfer(raw), null)
  }
})

test('recipient search encodes the query, requires the token and does not send money', async () => {
  const recipient = { walletId: destinationWalletId, username: 'John', maskedEmail: 'j***@example.com', currency: 'USD' }
  mock.method(globalThis, 'fetch', async (path: string, options: RequestInit) => {
    assert.equal(path, '/api/transfers/recipients/search?q=john%2Btest%40example.com')
    assert.equal((options.headers as Record<string, string>).Authorization, 'Bearer test-token')
    assert.equal(options.body, undefined)
    assert.equal(options.method, undefined)
    return new Response(JSON.stringify([recipient]), { status: 200 })
  })
  assert.deepEqual(await searchRecipients('test-token', 'john+test@example.com'), [recipient])
})

test('recovery keeps recipient display details without changing the transfer payload', async () => {
  const recipient = { walletId: destinationWalletId, username: 'John', maskedEmail: 'j***@example.com', currency: 'USD' as const }
  const operation = { ...pending, recipient }
  assert.deepEqual(readPendingTransfer(JSON.stringify(operation)), operation)
  mock.method(globalThis, 'fetch', async (_path: string, options: RequestInit) => {
    assert.deepEqual(JSON.parse(options.body as string), {
      destinationWalletId, amount: 3050, description: pending.description,
    })
    return new Response(JSON.stringify(transfer), { status: 201 })
  })
  await createTransfer('test-token', operation.key, operation)
})

test('invalid display details never discard a recoverable transfer', () => {
  const recipient = { walletId: sourceWalletId, username: 'Wrong person', maskedEmail: 'w***@example.com', currency: 'USD' }
  assert.deepEqual(readPendingTransfer(JSON.stringify({ ...pending, recipient })), pending)
  assert.deepEqual(readPendingTransfer(JSON.stringify({ ...pending, recipient: { walletId: destinationWalletId } })), pending)
})

test('POST and manual replay send identical keys and exact Java long JSON numbers', async () => {
  const calls: { path: string; options: RequestInit }[] = []
  mock.method(globalThis, 'fetch', async (path: string, options: RequestInit) => {
    calls.push({ path, options })
    return new Response(JSON.stringify(transfer), { status: 201 })
  })
  const operation = { ...pending, amount: '9223372036854775807' }
  await createTransfer('test-token', operation.key, operation)
  const restored = readPendingTransfer(JSON.stringify(operation))!
  await createTransfer('test-token', restored.key, restored)
  assert.equal(calls.length, 2)
  assert.equal(calls[0].path, '/api/transfers')
  assert.equal(calls[0].options.method, 'POST')
  assert.deepEqual(calls[0].options.headers, { Accept: 'application/json', 'Content-Type': 'application/json', Authorization: 'Bearer test-token', 'Idempotency-Key': operation.key })
  assert.equal(calls[0].options.body, `{"destinationWalletId":"${destinationWalletId}","amount":9223372036854775807,"description":${JSON.stringify(pending.description)}}`)
  assert.equal(calls[1].options.body, calls[0].options.body)
  assert.deepEqual(calls[1].options.headers, calls[0].options.headers)
})

test('GET lookups keep long amounts exact and never issue a transfer POST', async () => {
  const calls: { path: string; options: RequestInit }[] = []
  mock.method(globalThis, 'fetch', async (path: string, options: RequestInit) => {
    calls.push({ path, options })
    return new Response('{"amount":9223372036854775807,"balance":9007199254740993}', { status: 200 })
  })
  const result = await getTransferByKey('test-token', pending.key)
  assert.equal(result.amount, '9223372036854775807')
  await getTransfer('test-token', 'TRF-reference/with?characters')
  await getTransfers('test-token', 2)
  assert.deepEqual(calls.map((call) => call.path), ['/api/transfers/by-idempotency-key', '/api/transfers/TRF-reference%2Fwith%3Fcharacters', '/api/transfers?page=2&size=5'])
  assert.equal((calls[0].options.headers as Record<string, string>)['Idempotency-Key'], pending.key)
  assert.ok(calls.every((call) => !call.options.method || call.options.method === 'GET'))
})

test('server-confirmed success returns a terminal receipt', async () => {
  assert.deepEqual(await resolveTransfer(() => Promise.resolve(transfer)), { kind: 'confirmed', transfer })
})

test('business failure preserves the server reference for the receipt and history', async () => {
  mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify({ code: 'INSUFFICIENT_BALANCE', reference: 'TRF-failed' }), { status: 409 }))
  const outcome = await resolveTransfer(() => createTransfer('test-token', pending.key, pending))
  assert.equal(outcome.kind, 'rejected')
  if (outcome.kind === 'rejected') {
    assert.equal(outcome.code, 'INSUFFICIENT_BALANCE')
    assert.equal(outcome.reference, 'TRF-failed')
    assert.match(outcome.message, /enough funds/)
  }
})

for (const [code, status] of [
  ['SOURCE_WALLET_NOT_FOUND', 404], ['WALLET_NOT_FOUND', 404],
  ['SOURCE_WALLET_NOT_ACTIVE', 409], ['DESTINATION_WALLET_NOT_ACTIVE', 409],
  ['SELF_TRANSFER_NOT_ALLOWED', 400], ['BALANCE_LIMIT_EXCEEDED', 409],
  ['CURRENCY_MISMATCH', 409], ['VALIDATION_FAILED', 400],
] as const) {
  test(`${code} is a definitive rejection`, async () => {
    const result = await resolveTransfer(() => Promise.reject(new ApiError(code, 'Rejected by server', status)))
    assert.equal(result.kind, 'rejected')
  })
}

for (const [code, status] of [
  ['NETWORK_ERROR', 0], ['OPERATION_NOT_FOUND', 404],
  ['OPERATION_OUTCOME_UNKNOWN', 503], ['IDEMPOTENCY_IN_PROGRESS', 409],
  ['IDEMPOTENCY_KEY_CONFLICT', 409], ['SERVER_ERROR', 500],
  ['UNAUTHORIZED', 401], ['TOO_MANY_REQUESTS', 429],
  ['INSUFFICIENT_BALANCE', 503],
] as const) {
  test(`${code} (${status}) does not release an unconfirmed request`, async () => {
    const error = new ApiError(code, 'Unconfirmed', status)
    let calls = 0
    await assert.rejects(resolveTransfer(() => { calls++; return Promise.reject(error) }), (cause) => cause === error)
    assert.equal(calls, 1, 'No automatic POST retry is allowed')
  })
}
