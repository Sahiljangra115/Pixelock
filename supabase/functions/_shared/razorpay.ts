const encoder = new TextEncoder()

/** Hex HMAC SHA-256 of the raw body, compared in constant time. */
export async function verifySignature(rawBody: string, signature: string, secret: string): Promise<boolean> {
  if (!signature || !secret) return false
  const key = await crypto.subtle.importKey(
    "raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"],
  )
  const mac = new Uint8Array(await crypto.subtle.sign("HMAC", key, encoder.encode(rawBody)))
  const expected = Array.from(mac, (b) => b.toString(16).padStart(2, "0")).join("")
  if (expected.length !== signature.length) return false
  let diff = 0
  for (let i = 0; i < expected.length; i++) diff |= expected.charCodeAt(i) ^ signature.charCodeAt(i)
  return diff === 0
}

export interface PaidLink {
  linkId: string
  orderId: string
  amountPaid: number
}

/** Pulls the paid link out of a webhook event. Anything else is ignored. */
export function paidLinkFrom(event: unknown): PaidLink | null {
  const e = event as {
    event?: string
    payload?: { payment_link?: { entity?: { id?: string; reference_id?: string; amount_paid?: number; status?: string } } }
  }
  if (e?.event !== "payment_link.paid") return null
  const link = e.payload?.payment_link?.entity
  if (!link?.id || !link.reference_id || link.status !== "paid") return null
  return { linkId: link.id, orderId: link.reference_id, amountPaid: link.amount_paid ?? 0 }
}

export const PURPOSES = ["unlock_app", "end_timer"]

export function isUuid(value: unknown): value is string {
  return typeof value === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)
}

/** Razorpay wants at least 15 minutes. Twenty leaves room for clock drift. */
export function linkExpiry(nowMs: number): number {
  return Math.floor(nowMs / 1000) + 20 * 60
}
