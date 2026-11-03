export const maxAmount = 9223372036854775807n

export function centsFromInput(value: string): string | null {
  const normalized = value.trim().replace(',', '.')
  if (!/^\d{1,17}(?:\.\d{1,2})?$/.test(normalized)) return null
  const [whole, decimal = ''] = normalized.split('.')
  const cents = BigInt(whole) * 100n + BigInt(decimal.padEnd(2, '0'))
  return cents > 0n && cents <= maxAmount ? cents.toString() : null
}

export function decimalAmount(cents: string) {
  const amount = BigInt(cents)
  return `${amount / 100n}.${(amount % 100n).toString().padStart(2, '0')}`
}

export function money(cents: string) {
  const amount = BigInt(cents)
  return `$${(amount / 100n).toLocaleString('en-US')}.${(amount % 100n).toString().padStart(2, '0')}`
}
