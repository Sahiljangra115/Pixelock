-- Friend groups and daily totals.
--
-- Identity is Supabase anonymous sign in: no email, no phone, no name beyond the alias a person types.
-- The only activity stored is one row per day with two numbers. No app names, no timestamps, no location.
--
-- Clients can read and write only their own rows. Everything shared goes through board(), which
-- returns aliases and sums and never a user id.

create table public.groups (
  id text primary key,
  created_by uuid not null references auth.users (id) on delete cascade,
  created_at timestamptz not null default now()
);

create table public.members (
  group_id text not null references public.groups (id) on delete cascade,
  user_id uuid not null references auth.users (id) on delete cascade,
  alias text not null check (char_length(btrim(alias)) between 1 and 24),
  joined_at timestamptz not null default now(),
  primary key (group_id, user_id)
);

create index members_user_idx on public.members (user_id);

create table public.day_totals (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  date date not null,
  focused_min int not null check (focused_min between 0 and 2880),
  distracted_min int not null check (distracted_min between 0 and 1440),
  primary key (user_id, date)
);

alter table public.groups enable row level security;
alter table public.members enable row level security;
alter table public.day_totals enable row level security;

revoke all on public.groups, public.members, public.day_totals from anon, authenticated, public;
grant select on public.members to authenticated;
grant select, insert, update, delete on public.day_totals to authenticated;

create policy members_own on public.members
  for select to authenticated using (user_id = auth.uid());

create policy totals_own on public.day_totals
  for all to authenticated
  using (user_id = auth.uid())
  with check (user_id = auth.uid());

create function public.require_user() returns uuid
language plpgsql stable as $$
declare uid uuid := auth.uid();
begin
  if uid is null then raise exception 'not_signed_in'; end if;
  return uid;
end $$;

create function public.join_group(p_code text, p_alias text) returns void
language plpgsql security definer set search_path = public as $$
declare
  uid uuid := require_user();
  code text := upper(btrim(p_code));
begin
  if not exists (select 1 from groups where id = code) then
    raise exception 'group_not_found';
  end if;
  if (select count(*) from members where group_id = code and user_id <> uid) >= 20 then
    raise exception 'group_full';
  end if;
  insert into members (group_id, user_id, alias) values (code, uid, btrim(p_alias))
  on conflict (group_id, user_id) do update set alias = excluded.alias;
end $$;

create function public.create_group(p_alias text) returns text
language plpgsql security definer set search_path = public as $$
declare
  uid uuid := require_user();
  code text;
begin
  if (select count(*) from groups where created_by = uid) >= 10 then
    raise exception 'too_many_groups';
  end if;
  -- 64 random bits, so codes cannot be guessed. join_group has no rate limit of its own.
  -- Only the first 12 hex digits of a v4 uuid are all random, so two uuids supply these 16.
  code := translate(upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 12)
    || substr(replace(gen_random_uuid()::text, '-', ''), 1, 4)), '01', 'GH');
  insert into groups (id, created_by) values (code, uid);
  perform join_group(code, p_alias);
  return code;
end $$;

create function public.leave_group(p_code text) returns void
language plpgsql security definer set search_path = public as $$
declare
  uid uuid := require_user();
  code text := upper(btrim(p_code));
begin
  delete from members where group_id = code and user_id = uid;
  delete from groups g where g.id = code
    and not exists (select 1 from members m where m.group_id = g.id);
end $$;

create function public.board(p_code text, p_from date, p_to date)
returns table (alias text, focused_min bigint, distracted_min bigint, is_me boolean)
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
           coalesce(sum(t.focused_min), 0)::bigint,
           coalesce(sum(t.distracted_min), 0)::bigint,
           m.user_id = uid
    from members m
    left join day_totals t
      on t.user_id = m.user_id and t.date between p_from and p_to
    where m.group_id = code
    group by m.user_id, m.alias
    order by 2 desc, 3 asc;
end $$;

-- Removes everything this person has stored. Their groups go too if nobody else is in them.
create function public.delete_my_data() returns void
language plpgsql security definer set search_path = public as $$
declare uid uuid := require_user();
begin
  delete from day_totals where user_id = uid;
  delete from members where user_id = uid;
  delete from groups g where not exists (select 1 from members m where m.group_id = g.id);
end $$;

revoke execute on function public.require_user, public.join_group, public.create_group,
  public.leave_group, public.board, public.delete_my_data from public, anon;
grant execute on function public.require_user, public.join_group, public.create_group,
  public.leave_group, public.board, public.delete_my_data to authenticated;
