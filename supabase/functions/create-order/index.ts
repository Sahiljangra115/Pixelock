// Creates a Razorpay payment link for the signed in user.
// The price comes from server config. The client sends only why it is paying.
import { keyHeaders, publicKey, serviceKey } from "../_shared/keys.ts"
import { isUuid, linkExpiry, PURPOSES } from "../_shared/razorpay.ts"

const ORDER_WINDOW_MS = 60 * 60 * 1000
const MAX_ORDERS_PER_WINDOW = 10

const env = (name: string) => {
  const value = Deno.env.get(name)
  if (!value) throw new Error(`missing ${name}`)
  return value
}

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } })

async function userId(req: Request): Promise<string | null> {
  const res = await fetch(`${env("SUPABASE_URL")}/auth/v1/user`, {
    headers: { authorization: req.headers.get("authorization") ?? "", apikey: publicKey() },
  })
  if (!res.ok) return null
  return (await res.json()).id ?? null
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json(405, { error: "method" })
  const uid = await userId(req)
  if (!uid) return json(401, { error: "auth" })

  const body = await req.json().catch(() => null)
  if (!PURPOSES.includes(body?.purpose)) return json(400, { error: "purpose" })

  const orderId = crypto.randomUUID()
  if (!isUuid(orderId)) return json(500, { error: "id" })
  const amount = Number(env("UNLOCK_PRICE_PAISE"))
  const rest = `${env("SUPABASE_URL")}/rest/v1/unlock_orders`
  const headers = { ...keyHeaders(serviceKey()), "content-type": "application/json" }

  // Anyone can sign in anonymously, so one identity must not be able to mint links without end.
  const since = new Date(Date.now() - ORDER_WINDOW_MS).toISOString()
  const recent = await fetch(`${rest}?user_id=eq.${uid}&created_at=gte.${since}&select=id`, { headers })
  if (!recent.ok) return json(500, { error: "save" })
  if ((await recent.json()).length >= MAX_ORDERS_PER_WINDOW) return json(429, { error: "slow_down" })

  const saved = await fetch(rest, {
    method: "POST",
    headers,
    body: JSON.stringify({ id: orderId, user_id: uid, purpose: body.purpose, amount_paise: amount }),
  })
  if (!saved.ok) return json(500, { error: "save" })

  const basic = btoa(`${env("RAZORPAY_KEY_ID")}:${env("RAZORPAY_KEY_SECRET")}`)
  const link = await fetch("https://api.razorpay.com/v1/payment_links", {
    method: "POST",
    headers: { authorization: `Basic ${basic}`, "content-type": "application/json" },
    body: JSON.stringify({
      amount,
      currency: "INR",
      reference_id: orderId,
      description: "Pixelock unlock",
      expire_by: linkExpiry(Date.now()),
      notify: { sms: false, email: false },
      reminder_enable: false,
    }),
  })
  if (!link.ok) return json(502, { error: "gateway" })
  const created = await link.json()

  const patched = await fetch(`${rest}?id=eq.${orderId}`, {
    method: "PATCH",
    headers,
    body: JSON.stringify({ razorpay_link_id: created.id }),
  })
  if (!patched.ok) return json(500, { error: "save" })

  return json(200, { order_id: orderId, url: created.short_url, amount_paise: amount })
})
