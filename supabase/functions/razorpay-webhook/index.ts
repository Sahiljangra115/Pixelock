// Razorpay calls this after a payment. This is the only path that marks an order paid.
import { keyHeaders, serviceKey } from "../_shared/keys.ts"
import { paidLinkFrom, verifySignature } from "../_shared/razorpay.ts"

const env = (name: string) => {
  const value = Deno.env.get(name)
  if (!value) throw new Error(`missing ${name}`)
  return value
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return new Response("method", { status: 405 })

  // The signature covers the exact bytes, so the body must be read as text first.
  const raw = await req.text()
  const signature = req.headers.get("x-razorpay-signature") ?? ""
  if (!(await verifySignature(raw, signature, env("RAZORPAY_WEBHOOK_SECRET")))) {
    return new Response("signature", { status: 401 })
  }

  const paid = paidLinkFrom(JSON.parse(raw))
  if (!paid) return new Response("ignored", { status: 200 })

  const url = new URL(`${env("SUPABASE_URL")}/rest/v1/unlock_orders`)
  url.searchParams.set("id", `eq.${paid.orderId}`)
  url.searchParams.set("razorpay_link_id", `eq.${paid.linkId}`)
  url.searchParams.set("status", "eq.created")
  url.searchParams.set("amount_paise", `lte.${paid.amountPaid}`)

  // Conditional update: a replayed webhook, or a short payment, changes nothing.
  const res = await fetch(url, {
    method: "PATCH",
    headers: { ...keyHeaders(serviceKey()), "content-type": "application/json" },
    body: JSON.stringify({ status: "paid", paid_at: new Date().toISOString() }),
  })
  return new Response(res.ok ? "ok" : "retry", { status: res.ok ? 200 : 500 })
})
