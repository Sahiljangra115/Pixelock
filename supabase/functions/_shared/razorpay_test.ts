import { assertEquals } from "jsr:@std/assert@1"
import { isUuid, linkExpiry, paidLinkFrom, verifySignature } from "./razorpay.ts"

async function sign(body: string, secret: string) {
  const key = await crypto.subtle.importKey(
    "raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"],
  )
  const mac = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(body)))
  return Array.from(mac, (b) => b.toString(16).padStart(2, "0")).join("")
}

Deno.test("a correct signature passes", async () => {
  assertEquals(await verifySignature("body", await sign("body", "s"), "s"), true)
})

Deno.test("a changed body, wrong secret or empty values fail", async () => {
  const good = await sign("body", "s")
  assertEquals(await verifySignature("bodx", good, "s"), false)
  assertEquals(await verifySignature("body", good, "other"), false)
  assertEquals(await verifySignature("body", "", "s"), false)
  assertEquals(await verifySignature("body", good, ""), false)
  assertEquals(await verifySignature("body", good.slice(1), "s"), false)
})

const paidEvent = {
  event: "payment_link.paid",
  payload: { payment_link: { entity: { id: "plink_1", reference_id: "abc", amount_paid: 1600, status: "paid" } } },
}

Deno.test("paid events are read", () => {
  assertEquals(paidLinkFrom(paidEvent), { linkId: "plink_1", orderId: "abc", amountPaid: 1600 })
})

Deno.test("other events and half filled events are ignored", () => {
  assertEquals(paidLinkFrom({ ...paidEvent, event: "payment_link.cancelled" }), null)
  assertEquals(paidLinkFrom({ event: "payment_link.paid", payload: {} }), null)
  assertEquals(paidLinkFrom(null), null)
  const unpaid = structuredClone(paidEvent)
  unpaid.payload.payment_link.entity.status = "created"
  assertEquals(paidLinkFrom(unpaid), null)
})

Deno.test("ids and expiry", () => {
  assertEquals(isUuid(crypto.randomUUID()), true)
  assertEquals(isUuid("1; drop table"), false)
  assertEquals(linkExpiry(0), 1200)
})
