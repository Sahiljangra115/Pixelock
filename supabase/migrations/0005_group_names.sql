-- Groups get a name people pick. The code stays the secret, so only members can read the name.

alter table public.groups add column name text not null default ''
  check (char_length(name) <= 30);

drop function public.create_group(text);

create function public.create_group(p_alias text, p_name text default '') returns text
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
  insert into groups (id, created_by, name) values (code, uid, btrim(p_name));
  perform join_group(code, p_alias);
  return code;
end $$;

create function public.group_name(p_code text) returns text
language plpgsql stable security definer set search_path = public as $$
declare
  uid uuid := require_user();
  code text := upper(btrim(p_code));
begin
  if not exists (select 1 from members m where m.group_id = code and m.user_id = uid) then
    raise exception 'not_a_member';
  end if;
  return (select g.name from groups g where g.id = code);
end $$;

revoke execute on function public.create_group, public.group_name from public, anon;
grant execute on function public.create_group, public.group_name to authenticated;
