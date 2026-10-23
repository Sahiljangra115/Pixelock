// Runs the migrations on a throwaway in-memory Postgres and checks who can see what.
// Usage: npm i @electric-sql/pglite && node rls_check.mjs <path to supabase/migrations>
import { PGlite } from '@electric-sql/pglite'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import assert from 'node:assert/strict'

const dir = process.argv[2]
const db = new PGlite()

// The parts of Supabase the migrations lean on.
await db.exec(`
  create role anon nologin;
  create role authenticated nologin;
  create role service_role nologin bypassrls;
  grant usage on schema public to anon, authenticated, service_role;
  create schema auth;
  grant usage on schema auth to anon, authenticated, service_role;
  create table auth.users (id uuid primary key);
  create function auth.uid() returns uuid language sql stable as
    $$ select nullif(current_setting('request.jwt.sub', true), '')::uuid $$;
`)
for (const f of readdirSync(dir).sort()) await db.exec(readFileSync(join(dir, f), 'utf8'))

const A = '00000000-0000-0000-0000-00000000000a'
const B = '00000000-0000-0000-0000-00000000000b'
const C = '00000000-0000-0000-0000-00000000000c'
await db.exec(`insert into auth.users values ('${A}'), ('${B}'), ('${C}')`)

async function as(uid, sql, params) {
  await db.exec(`reset role; set request.jwt.sub = '${uid ?? ''}'; set role ${uid ? 'authenticated' : 'anon'};`)
  try {
    return (await db.query(sql, params)).rows
  } finally {
    await db.exec('reset role')
  }
}
async function fails(uid, sql, params, pattern) {
  await assert.rejects(() => as(uid, sql, params), pattern, sql)
}

const pushTotal = `insert into public.day_totals (date, focused_min, distracted_min) values ($1, $2, $3)
  on conflict (user_id, date) do update set focused_min = excluded.focused_min, distracted_min = excluded.distracted_min`

const [{ create_group: code }] = await as(A, `select public.create_group('Asha')`)
assert.match(code, /^[2-9A-H]{16}$/)
await as(B, `select public.join_group($1, 'Ben')`, [code.toLowerCase() + '  '])

await as(A, pushTotal, ['2026-10-29', 100, 10])
await as(A, pushTotal, ['2026-10-30', 50, 5])
await as(A, pushTotal, ['2026-10-30', 60, 6])
await as(B, pushTotal, ['2026-10-30', 200, 1])
await as(C, pushTotal, ['2026-10-30', 999, 1])

// Board: aliases and sums only, best first, members only.
const board = await as(A, `select * from public.board($1, '2026-10-29', '2026-10-30')`, [code])
assert.deepEqual(board.map(r => [r.alias, Number(r.focused_min), Number(r.distracted_min), r.is_me]),
  [['Ben', 200, 1, false], ['Asha', 160, 16, true]])
assert.ok(!Object.keys(board[0]).includes('user_id'))
await fails(C, `select * from public.board($1, '2026-10-29', '2026-10-30')`, [code], /not_a_member/)
await fails(null, `select * from public.board($1, '2026-10-29', '2026-10-30')`, [code], /permission denied/)

// Nobody reads another person's rows directly.
assert.equal((await as(A, `select * from public.day_totals`)).length, 2)
assert.equal((await as(A, `select * from public.members`)).length, 1)
await fails(A, `select * from public.groups`, [], /permission denied/)
await fails(null, `select * from public.day_totals`, [], /permission denied/)
await fails(A, `insert into public.members (group_id, user_id, alias) values ($1, $2, 'x')`, [code, C], /permission denied/)
await fails(A, `insert into public.day_totals (user_id, date, focused_min, distracted_min) values ($1, '2026-01-01', 1, 1)`, [B], /row-level security/)
assert.equal((await as(A, `update public.day_totals set focused_min = 1 where user_id = $1 returning *`, [B])).length, 0)
assert.equal((await as(A, `delete from public.day_totals where user_id = $1 returning *`, [B])).length, 0)

// Bad input and limits.
await fails(A, pushTotal, ['2026-10-28', -1, 0], /check constraint/)
await fails(A, pushTotal, ['2026-10-28', 0, 5000], /check constraint/)
await fails(B, `select public.join_group('NOPE', 'x')`, [], /group_not_found/)
await fails(B, `select public.join_group($1, '   ')`, [code], /check constraint/)
await fails(null, `select public.create_group('x')`, [], /permission denied/)

// Leaving, then deleting all data.
await as(B, `select public.leave_group($1)`, [code])
assert.equal((await as(A, `select * from public.board($1, '2026-10-29', '2026-10-30')`, [code])).length, 1)
await as(A, `select public.delete_my_data()`)
assert.equal((await as(A, `select * from public.day_totals`)).length, 0)
assert.equal((await as(A, `select * from public.members`)).length, 0)
await fails(B, `select public.join_group($1, 'Ben')`, [code], /group_not_found/)

// Paid orders. Only the service key can mark one paid, and it pays out once.
const order = '11111111-1111-4111-8111-111111111111'
await db.exec(`insert into public.unlock_orders (id, user_id, purpose, amount_paise) values ('${order}', '${B}', 'unlock_app', 1600)`)
await fails(B, `update public.unlock_orders set status = 'paid' where id = $1`, [order], /permission denied/)
await fails(B, `insert into public.unlock_orders (id, user_id, purpose, amount_paise) values (gen_random_uuid(), $1, 'unlock_app', 1)`, [B], /permission denied/)
assert.equal((await as(C, `select id from public.unlock_orders`)).length, 0)
assert.equal((await as(B, `select public.consume_order($1)`, [order]))[0].consume_order, false)
await db.exec(`update public.unlock_orders set status = 'paid' where id = '${order}'`)
assert.equal((await as(C, `select public.consume_order($1)`, [order]))[0].consume_order, false)
assert.equal((await as(B, `select public.consume_order($1)`, [order]))[0].consume_order, true)
assert.equal((await as(B, `select public.consume_order($1)`, [order]))[0].consume_order, false)
assert.equal((await as(B, `select status from public.unlock_orders`))[0].status, 'consumed')

console.log('rls checks passed')
