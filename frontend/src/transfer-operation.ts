import { ApiError } from './api.ts'
import type { Transfer, TransferPayload } from './api.ts'
import { maxAmount } from './money.ts'

export interface PendingTransfer extends TransferPayload { key: string }
export type TransferOutcome = { kind: 'confirmed'; transfer: Transfer } | { kind: 'rejected'; code: string; message: string; reference?: string }
export const walletIdPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

// Only definitive server rejections release the saved request. A timeout, 404 lookup,
// 5xx, key conflict or in-progress response must retain the original key and payload.
const rejectionCodes = new Set([
  'INVALID_AMOUNT', 'VALIDATION_FAILED', 'INVALID_REQUEST', 'INVALID_REQUEST_BODY',
  'INVALID_IDEMPOTENCY_KEY', 'SOURCE_WALLET_NOT_FOUND', 'WALLET_NOT_FOUND',
  'SOURCE_WALLET_NOT_ACTIVE', 'DESTINATION_WALLET_NOT_ACTIVE', 'SELF_TRANSFER_NOT_ALLOWED',
  'INSUFFICIENT_BALANCE', 'BALANCE_LIMIT_EXCEEDED', 'CURRENCY_MISMATCH',
  'WALLET_ACCESS_DENIED', 'FORBIDDEN', 'USER_NOT_FOUND',
])

export async function resolveTransfer(action: () => Promise<Transfer>): Promise<TransferOutcome> {
  try { return { kind: 'confirmed', transfer: await action() } }
  catch (error) {
    if (error instanceof ApiError && error.status >= 400 && error.status < 500 && rejectionCodes.has(error.code)) {
      return { kind: 'rejected', code: error.code, message: error.message, reference: error.reference }
    }
    throw error
  }
}

export function readPendingTransfer(raw: string | null): PendingTransfer | null {
  if (!raw) return null
  try {
    const value: unknown = JSON.parse(raw)
    if (!value || typeof value !== 'object') return null
    const item = value as Partial<PendingTransfer>
    if (typeof item.key !== 'string' || !item.key.trim() || item.key.length > 255
      || typeof item.destinationWalletId !== 'string' || !walletIdPattern.test(item.destinationWalletId)
      || typeof item.amount !== 'string' || !/^\d{1,19}$/.test(item.amount)
      || BigInt(item.amount) <= 0n || BigInt(item.amount) > maxAmount
      || typeof item.description !== 'string' || item.description.length > 255) return null
    // Explicitly copy only recovery fields; never restore credentials from storage.
    return { key: item.key, destinationWalletId: item.destinationWalletId, amount: item.amount, description: item.description }
  } catch { return null }
}
