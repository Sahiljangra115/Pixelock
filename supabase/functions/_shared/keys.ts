/**
 * New projects use sb_ keys, older ones use JWT keys. The runtime gives new keys as a JSON
 * dictionary. An sb_ key is not a token, so it must never go in the Authorization header.
 */
export function pickKey(dictionary: string | undefined, legacy: string | undefined): string {
  const key = (dictionary ? JSON.parse(dictionary).default : undefined) ?? legacy
  if (!key) throw new Error("missing API key")
  return key
}

export function keyHeaders(key: string): Record<string, string> {
  return key.startsWith("sb_") ? { apikey: key } : { apikey: key, authorization: `Bearer ${key}` }
}

export const publicKey = () =>
  pickKey(Deno.env.get("SUPABASE_PUBLISHABLE_KEYS"), Deno.env.get("SUPABASE_ANON_KEY"))

export const serviceKey = () =>
  pickKey(Deno.env.get("SUPABASE_SECRET_KEYS"), Deno.env.get("SUPABASE_SERVICE_ROLE_KEY"))
