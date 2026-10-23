-- Paid unlocks. A row becomes 'paid' only from the signed Razorpay webhook, using the service key.
-- Clients can read their own rows and consume a paid row once. They can never mark one paid.

create table public.unlock_orders (
  id uuid primary key,
  user_id uuid not null references auth.users (id) on delete cascade,
  purpose text not null check (purpose in ('unlock_app', 'end_timer')),
  amount_paise int not null check (amount_paise > 0),
  razorpay_link_id text unique,
  status text not null default 'created' check (status in ('created', 'paid', 'consumed')),
  created_at timestamptz not null default now(),
  paid_at timestamptz,
  consumed_at timestamptz
);

create index unlock_orders_user_idx on public.unlock_orders (user_id);

alter table public.unlock_orders enable row level security;
revoke all on public.unlock_orders from anon, authenticated, public;
grant select (id, purpose, status) on public.unlock_orders to authenticated;

create policy orders_own on public.unlock_orders
  for select to authenticated using (user_id = auth.uid());

-- Returns true once, for the first caller. A paid order can never unlock twice.
create function public.consume_order(p_id uuid) returns boolean
language plpgsql security definer set search_path = public as $$
declare
  uid uuid := require_user();
  hit int;
begin
  update unlock_orders
    set status = 'consumed', consumed_at = now()
    where id = p_id and user_id = uid and status = 'paid';
  get diagnostics hit = row_count;
  return hit = 1;
end $$;

revoke execute on function public.consume_order from public, anon;
grant execute on function public.consume_order to authenticated;

-- delete_my_data keeps order rows: they are the payment record.
