// Invite links must be https to be tappable in chat apps. This only forwards to the app.
import { inviteTarget } from "../_shared/invite.ts"

const download = `${Deno.env.get("SUPABASE_URL")}/storage/v1/object/public/app/pixelock.apk`

Deno.serve((req) => {
  const target = inviteTarget(new URL(req.url).searchParams.get("c"), download)
  if (!target) return new Response("Bad invite", { status: 404 })
  return new Response(null, { status: 302, headers: { location: target } })
})
