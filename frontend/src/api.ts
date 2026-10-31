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
  INVALID_CREDENTIALS: 'Email hoặc mật khẩu chưa chính xác. Vui lòng thử lại.',
  EMAIL_ALREADY_EXISTS: 'Email này đã được đăng ký. Bạn có thể đăng nhập bằng tài khoản hiện có.',
  INVALID_PASSWORD: 'Mật khẩu không được vượt quá 72 byte UTF-8. Vui lòng dùng mật khẩu ngắn hơn.',
  VALIDATION_FAILED: 'Thông tin chưa hợp lệ. Vui lòng kiểm tra lại các trường bên dưới.',
  UNAUTHORIZED: 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.',
  FORBIDDEN: 'Bạn không có quyền thực hiện thao tác này.',
  WALLET_NOT_FOUND: 'Bạn chưa có ví. Hãy tạo ví để bắt đầu.',
  WALLET_ALREADY_EXISTS: 'Bạn đã có ví. Đang tải lại thông tin ví.',
  WALLET_NOT_ACTIVE: 'Ví đang bị khóa. Hiện chưa thể tạo yêu cầu nạp tiền.',
  INVALID_AMOUNT: 'Số tiền nạp phải lớn hơn 0.',
  IDEMPOTENCY_KEY_CONFLICT: 'Mã yêu cầu đã được sử dụng. Vui lòng kiểm tra trạng thái yêu cầu hiện tại.',
  IDEMPOTENCY_IN_PROGRESS: 'Yêu cầu đang được xử lý. Vui lòng kiểm tra lại sau.',
  OPERATION_NOT_FOUND: 'Chưa tìm thấy kết quả. Yêu cầu có thể vẫn đang được xử lý.',
  OPERATION_OUTCOME_UNKNOWN: 'Chưa xác nhận được kết quả. Hãy kiểm tra trạng thái hoặc gửi lại cùng yêu cầu.',
  TOP_UP_NOT_FOUND: 'Không tìm thấy giao dịch nạp tiền này.',
}

export class ApiError extends Error {
  constructor(public code: string, message: string, public status = 0) {
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
    throw new ApiError('NETWORK_ERROR', 'Không thể kết nối máy chủ. Vui lòng kiểm tra kết nối và thử lại.')
  }

  // Preserve Java long money fields exactly, even above JavaScript's safe-integer range.
  let data: unknown = null
  try {
    data = JSON.parse(raw.replace(/("(?:balance|amount)"\s*:\s*)(-?\d+)(?=\s*[,}])/g, '$1"$2"'))
  } catch { /* Invalid responses are handled below. */ }
  if (!response.ok) {
    const code = data && typeof data === 'object' && 'code' in data && typeof data.code === 'string' ? data.code : 'SERVER_ERROR'
    throw new ApiError(code, messages[code] || (response.status === 429
      ? 'Bạn đã gửi quá nhiều yêu cầu. Vui lòng thử lại sau ít phút.'
      : 'Máy chủ chưa thể xử lý yêu cầu. Vui lòng thử lại sau.'), response.status)
  }
  if (!data) throw new ApiError('INVALID_RESPONSE', 'Phản hồi máy chủ chưa hợp lệ. Vui lòng thử lại.')
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
