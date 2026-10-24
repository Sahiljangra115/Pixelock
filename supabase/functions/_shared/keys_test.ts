import { keyHeaders, pickKey } from "./keys.ts"

const check = (name: string, ok: boolean) => Deno.test(name, () => { if (!ok) throw new Error(name) })

check("new keys win", pickKey('{"default":"sb_secret_x"}', "old") === "sb_secret_x")
check("old keys still work", pickKey(undefined, "old") === "old")
check("an sb key never goes in Authorization", !("authorization" in keyHeaders("sb_secret_x")))
check("a JWT key keeps Authorization", keyHeaders("eyJ").authorization === "Bearer eyJ")
Deno.test("no key at all is an error", () => {
  let threw = false
  try { pickKey(undefined, undefined) } catch { threw = true }
  if (!threw) throw new Error("expected a throw")
})
