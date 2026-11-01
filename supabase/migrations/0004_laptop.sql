-- Laptop sync.
--
-- The laptop signs in as its own anonymous user and is linked to a phone user with a short code
-- that the phone shows once. After that the laptop writes days into the phone user's row set,
-- so one id owns both devices and the board can show phone, laptop and total.
--
-- Per app names live in laptop_days.apps. Only the owner can read them. board() returns sums only.

create table public.pair_codes (
  code_hash bytea primary key,
  owner uuid not null references auth.users (id) on delete cascade,
  expires_at timestamptz not null
);

create table public.device_links (
  laptop uuid primary key references auth.users (id) on delete cascade,
  owner uuid not null references auth.users (id) on delete cascade,
  linked_at timestamptz not null default now()
);
create index device_links_owner_idx on public.device_links (owner);

create table public.laptop_days (
  owner uuid not null references auth.users (id) on delete cascade,
  date date not null,
  focused_min int not null check (focused_min between 0 and 1440),
  distracted_min int not null check (distracted_min between 0 and 1440),
  -- [{"app": "Code", "cat": "f", "min": 42}], cat is f (productive) or d (distracting)
  apps jsonb not null default '[]' check (
    jsonb_typeof(apps) = 'array' and jsonb_array_length(apps) <= 60 and octet_length(apps::text) <= 12000
  ),
  updated_at timestamptz not null default now(),
  primary key (owner, date)
);

alter table public.pair_codes enable row level security;
alter table public.device_links enable row level security;
alter table public.laptop_days enable row level security;

revoke all on public.pair_codes, public.device_links, public.laptop_days from anon, authenticated, public;
grant select on public.laptop_days to authenticated;

create policy laptop_days_own on public.laptop_days
  for select to authenticated using (owner = auth.uid());

-- Phone: makes a one time code, valid for 10 minutes. 48 random bits, and redeeming needs a sign in.
create function public.create_pair_code() returns text
language plpgsql security definer set search_path = public as $$
declare
  uid uuid := require_user();
  code text;
begin
  delete from pair_codes where expires_at < now();
  code := translate(upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 12)), '01', 'GH');
  insert into pair_codes (code_hash, owner, expires_at)
  values (sha256(convert_to(code, 'utf8')), uid, now() + interval '10 minutes');
  return code;
end $$;

-- Laptop: spends the code and becomes a device of the phone user.
create function public.redeem_pair_code(p_code text) returns void
language plpgsql security definer set search_path = public as $$
declare
  uid uuid := require_user();
  hit uuid;
begin
  delete from pair_codes
  where code_hash = sha256(convert_to(upper(btrim(p_code)), 'utf8')) and expires_at > now()
  returning owner into hit;
  if hit is null then raise exception 'bad_pair_code'; end if;
  if hit = uid then raise exception 'bad_pair_code'; end if;
  insert into device_links (laptop, owner) values (uid, hit)
  on conflict (laptop) do update set owner = excluded.owner, linked_at = now();
end $$;

-- Laptop: replaces one day. Sent whole each time, so a repeat is harmless.
create function public.push_laptop_day(p_date date, p_focused int, p_distracted int, p_apps jsonb)
returns void
language plpgsql security definer set search_path = public as $$
declare
  uid uuid := require_user();
  who uuid;
begin
  select owner into who from device_links where laptop = uid;
  if who is null then raise exception 'not_linked'; end if;
  if p_date < current_date - 7 or p_date > current_date + 1 then raise exception 'bad_date'; end if;
  insert into laptop_days (owner, date, focused_min, distracted_min, apps, updated_at)
  values (who, p_date, least(greatest(p_focused, 0), 1440), least(greatest(p_distracted, 0), 1440),
          coalesce(p_apps, '[]'), now())
  on conflict (owner, date) do update
    set focused_min = excluded.focused_min, distracted_min = excluded.distracted_min,
        apps = excluded.apps, updated_at = now();
end $$;

-- focused_min and distracted_min stay the totals for both devices, so an older app still reads right.
drop function public.board(text, date, date);
create function public.board(p_code text, p_from date, p_to date)
returns table (
  alias text, focused_min bigint, distracted_min bigint, is_me boolean,
  phone_min bigint, laptop_min bigint, laptop_distracted_min bigint
)
language plpgsql stable security definer set search_path = public as $$
declare
  uid uuid := require_user();
  code text := upper(btrim(p_code));
begin
  if not exists (select 1 from members m where m.group_id = code and m.user_id = uid) then
    raise exception 'not_a_member';
  end if;
  return query
    select m.alias,
           (p.f + l.f)::bigint,
           (p.d + l.d)::bigint,
           m.user_id = uid,
           p.f::bigint, l.f::bigint, l.d::bigint
    from members m
    cross join lateral (
      select coalesce(sum(t.focused_min), 0) f, coalesce(sum(t.distracted_min), 0) d
      from day_totals t where t.user_id = m.user_id and t.date between p_from and p_to
    ) p
    cross join lateral (
      select coalesce(sum(x.focused_min), 0) f, coalesce(sum(x.distracted_min), 0) d
      from laptop_days x where x.owner = m.user_id and x.date between p_from and p_to
    ) l
    where m.group_id = code
    order by 2 desc, 3 asc;
end $$;

create or replace function public.delete_my_data() returns void
language plpgsql security definer set search_path = public as $$
declare uid uuid := require_user();
begin
  delete from day_totals where user_id = uid;
  delete from laptop_days where owner = uid;
  delete from device_links where owner = uid;
  delete from pair_codes where owner = uid;
  delete from members where user_id = uid;
  delete from groups g where not exists (select 1 from members m where m.group_id = g.id);
end $$;

revoke execute on function public.create_pair_code, public.redeem_pair_code, public.push_laptop_day,
  public.board from public, anon;
grant execute on function public.create_pair_code, public.redeem_pair_code, public.push_laptop_day,
  public.board to authenticated;
