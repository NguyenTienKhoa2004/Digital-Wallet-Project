export interface User {
  userId: string
  username: string
  email: string
  role: string
}

export interface LoginResult {
  accessToken: string
  tokenType: string
  expiresIn: number
  user: User
}

export interface Credentials {
  email: string
  password: string
}

const messages: Record<string, string> = {
  INVALID_CREDENTIALS: 'Incorrect email or password. Please try again.',
  EMAIL_ALREADY_EXISTS: 'This email is already registered. You can sign in with your existing account.',
  INVALID_PASSWORD: 'Password must not exceed 72 UTF-8 bytes. Please use a shorter password.',
  VALIDATION_FAILED: 'Some details are invalid. Please check the fields below.',
  UNAUTHORIZED: 'Your session has expired. Please sign in again.',
  FORBIDDEN: 'You do not have permission to perform this action.',
  WALLET_NOT_FOUND: 'The wallet could not be found. Check the wallet ID and try again.',
  WALLET_ALREADY_EXISTS: 'You already have a wallet. Refreshing your wallet details.',
  WALLET_NOT_ACTIVE: 'Your wallet is frozen. You cannot add funds right now.',
  INVALID_AMOUNT: 'The amount must be greater than zero.',
  IDEMPOTENCY_KEY_CONFLICT: 'This request ID has already been used. Please check the existing request status.',
  IDEMPOTENCY_IN_PROGRESS: 'Your request is being processed. Please check again later.',
  OPERATION_NOT_FOUND: 'No result found yet. Your request may still be processing.',
  OPERATION_OUTCOME_UNKNOWN: 'The outcome is not yet confirmed. Check the status or resend the same request.',
  SOURCE_WALLET_NOT_FOUND: 'Create your FlowPay wallet before sending money.',
  SOURCE_WALLET_NOT_ACTIVE: 'Your wallet is frozen. Transfers are unavailable.',
  DESTINATION_WALLET_NOT_ACTIVE: 'The recipient wallet is frozen and cannot receive money.',
  SELF_TRANSFER_NOT_ALLOWED: 'Choose a wallet other than your own.',
  INSUFFICIENT_BALANCE: 'Your wallet does not have enough funds for this transfer.',
  BALANCE_LIMIT_EXCEEDED: 'The recipient wallet has reached its balance limit.',
  CURRENCY_MISMATCH: 'Both wallets must support USD transfers.',
  WALLET_ACCESS_DENIED: 'You do not have permission to send from this wallet.',
  TRANSFER_NOT_FOUND: 'This transfer could not be found or is not accessible.',
  INVALID_REQUEST_BODY: 'Check the recipient, amount and note.',
  INVALID_IDEMPOTENCY_KEY: 'The request ID is invalid. Please start a new request.',
  TOP_UP_NOT_FOUND: 'This top-up transaction could not be found.',
}

export class ApiError extends Error {
  constructor(public code: string, message: string, public status = 0, public reference?: string) {
    super(message)
  }
}

async function request<T>(path: string, options: RequestInit = {}, token?: string): Promise<T> {
  let response: Response
  let raw: string
  try {
    response = await fetch(`/api${path}`, {
      signal: AbortSignal.timeout(15_000),
      cache: 'no-store',
      credentials: 'omit',
      ...options,
      headers: {
        Accept: 'application/json',
        ...(options.body ? { 'Content-Type': 'application/json' } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...options.headers,
      },
    })
    raw = await response.text()
  } catch {
    throw new ApiError('NETWORK_ERROR', 'Unable to connect to the server. Please check your connection and try again.')
  }

  // Preserve Java long money fields exactly, even above JavaScript's safe-integer range.
  let data: unknown = null
  try {
    data = JSON.parse(raw.replace(/("(?:balance|amount)"\s*:\s*)(-?\d+)(?=\s*[,}])/g, '$1"$2"'))
  } catch { /* Invalid responses are handled below. */ }
  if (!response.ok) {
    const code = data && typeof data === 'object' && 'code' in data && typeof data.code === 'string' ? data.code : 'SERVER_ERROR'
    const reference = data && typeof data === 'object' && 'reference' in data && typeof data.reference === 'string' ? data.reference : undefined
    throw new ApiError(code, messages[code] || (response.status === 429
      ? 'Too many requests. Please try again in a few minutes.'
      : 'The server could not process your request. Please try again later.'), response.status, reference)
  }
  if (!data) throw new ApiError('INVALID_RESPONSE', 'The server returned an invalid response. Please try again.')
  return data as T
}

export const login = (credentials: Credentials) => request<LoginResult>('/auth/login', { method: 'POST', body: JSON.stringify(credentials) })
export const register = (data: Credentials & { username: string }) => request<User>('/auth/register', { method: 'POST', body: JSON.stringify(data) })

export interface Wallet {
  walletId: string
  balance: string
  currency: 'USD'
  status: 'ACTIVE' | 'FROZEN'
  createdAt: string
  updatedAt: string
}

export interface TopUp {
  transactionId: string
  reference: string
  destinationWalletId: string
  amount: string
  currency: 'USD'
  status: 'PENDING' | 'SUCCESS' | 'FAILED'
  description: string | null
  createdAt: string
  completedAt: string | null
  failureCode: string | null
}

export interface TopUpHistory {
  items: TopUp[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface TopUpPayload { amount: string; description: string }

export const getWallet = (token: string) => request<Wallet>('/wallets/me', {}, token)
export const createWallet = (token: string) => request<Wallet>('/wallets', { method: 'POST' }, token)
export const getTopUps = (token: string, page: number) => request<TopUpHistory>(`/top-ups?page=${page}&size=5`, {}, token)
export const getTopUp = (token: string, reference: string) => request<TopUp>(`/top-ups/${encodeURIComponent(reference)}`, {}, token)
export const getTopUpByKey = (token: string, key: string) => request<TopUp>('/top-ups/by-idempotency-key', { headers: { 'Idempotency-Key': key } }, token)
export const createTopUp = (token: string, key: string, payload: TopUpPayload) => request<TopUp>('/top-ups', {
  method: 'POST',
  headers: { 'Idempotency-Key': key },
  // amount is a validated positive decimal integer; send an exact JSON number to Java.
  body: `{"amount":${payload.amount},"description":${JSON.stringify(payload.description)}}`,
}, token)

export interface Transfer extends TopUp { sourceWalletId: string }
export interface TransferHistory extends Omit<TopUpHistory, 'items'> { items: Transfer[] }
export interface TransferPayload extends TopUpPayload { destinationWalletId: string }

export interface Recipient {
  walletId: string
  username: string
  maskedEmail: string
  currency: 'USD'
}

export const searchRecipients = (token: string, query: string) => request<Recipient[]>(
  `/transfers/recipients/search?q=${encodeURIComponent(query)}`, {}, token,
)

export const getTransfers = (token: string, page: number) => request<TransferHistory>(`/transfers?page=${page}&size=5`, {}, token)
export const getTransfer = (token: string, reference: string) => request<Transfer>(`/transfers/${encodeURIComponent(reference)}`, {}, token)
export const getTransferByKey = (token: string, key: string) => request<Transfer>('/transfers/by-idempotency-key', { headers: { 'Idempotency-Key': key } }, token)
export const createTransfer = (token: string, key: string, payload: TransferPayload) => request<Transfer>('/transfers', {
  method: 'POST',
  headers: { 'Idempotency-Key': key },
  // Keep Java long cents exact; never convert monetary values to Number.
  body: `{"destinationWalletId":${JSON.stringify(payload.destinationWalletId)},"amount":${payload.amount},"description":${JSON.stringify(payload.description)}}`,
}, token)
