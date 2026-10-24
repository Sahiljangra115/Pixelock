import { inviteTarget } from "./invite.ts"

const check = (name: string, ok: boolean) => Deno.test(name, () => { if (!ok) throw new Error(name) })

const apk = "https://x.supabase.co/storage/v1/object/public/app/pixelock.apk"

check(
  "good code opens the app, and falls back to the download",
  inviteTarget("23456789ABCDEFGH", apk) ===
    "intent://join/23456789ABCDEFGH#Intent;scheme=pixelock;S.browser_fallback_url=https%3A%2F%2Fx.supabase.co%2Fstorage%2Fv1%2Fobject%2Fpublic%2Fapp%2Fpixelock.apk;end",
)
check("missing code is refused", inviteTarget(null, apk) === null)
check("junk cannot reach the redirect", inviteTarget("23456789ABCDEFGH;S.x=y", apk) === null)
check("lower case is refused", inviteTarget("23456789abcdefgh", apk) === null)
