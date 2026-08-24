/**
 * Formatting helpers.
 *
 * Money arrives as integer cents and is only ever divided at the last moment,
 * for display. Nothing upstream of this file works in decimals.
 */

const money = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  maximumFractionDigits: 0,
})

export function formatMoney(cents: number): string {
  return money.format(cents / 100)
}

/** Compact form for dense places like a seat tooltip. */
export function formatMoneyShort(cents: number): string {
  return `₹${Math.round(cents / 100).toLocaleString('en-IN')}`
}

export function formatDate(iso: string, timeZone?: string): string {
  return new Intl.DateTimeFormat('en-GB', {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    timeZone,
  }).format(new Date(iso))
}

export function formatTime(iso: string, timeZone?: string): string {
  return new Intl.DateTimeFormat('en-GB', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    timeZone,
  }).format(new Date(iso))
}

/** Day and month for the date block on a listing row. */
export function dateParts(iso: string, timeZone?: string) {
  const date = new Date(iso)
  return {
    day: new Intl.DateTimeFormat('en-GB', { day: '2-digit', timeZone }).format(date),
    month: new Intl.DateTimeFormat('en-GB', { month: 'short', timeZone })
      .format(date)
      .toUpperCase(),
    year: new Intl.DateTimeFormat('en-GB', { year: 'numeric', timeZone }).format(date),
    weekday: new Intl.DateTimeFormat('en-GB', { weekday: 'short', timeZone })
      .format(date)
      .toUpperCase(),
  }
}

/** "1,847 seats" - never "1000+", which hides the number it already knows. */
export function formatCount(n: number, noun: string, pluralNoun?: string): string {
  const word = n === 1 ? noun : (pluralNoun ?? `${noun}s`)
  return `${n.toLocaleString('en-IN')} ${word}`
}
