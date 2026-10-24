/** Same alphabet as create_group. Anything else never reaches the redirect. */
export function inviteTarget(code: string | null, downloadUrl: string): string | null {
  if (!code || !/^[2-9A-H]{16}$/.test(code)) return null
  // Android opens the app when it is there. Without it, the browser goes to the fallback.
  return `intent://join/${code}#Intent;scheme=pixelock;S.browser_fallback_url=${encodeURIComponent(downloadUrl)};end`
}
