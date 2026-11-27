begin;
alter table public.profiles add column change_at bigint not null default (floor(extract(epoch from clock_timestamp())*1000)::bigint), add column change_id text not null default '';
alter table public.groups add column change_at bigint not null default (floor(extract(epoch from clock_timestamp())*1000)::bigint), add column change_id text not null default '';
alter table public.group_members add column change_at bigint not null default (floor(extract(epoch from clock_timestamp())*1000)::bigint), add column change_id text not null default '';
alter table public.shopping_lists add column change_at bigint not null default (floor(extract(epoch from clock_timestamp())*1000)::bigint), add column change_id text not null default '';
grant update(change_at,change_id) on public.profiles,public.groups,public.group_members,public.shopping_lists to authenticated;
grant insert(change_at,change_id) on public.groups,public.group_members,public.shopping_lists to authenticated;
create function buylist_private.guard_change_stamp() returns trigger language plpgsql set search_path='' as $$
declare now_ms bigint := floor(extract(epoch from clock_timestamp())*1000)::bigint;
begin
  if tg_op='UPDATE' then
    if tg_table_name='shopping_lists' and
      (to_jsonb(new)-'revision'-'change_at'-'change_id')=(to_jsonb(old)-'revision'-'change_at'-'change_id')
      and new.change_at=old.change_at and new.change_id=old.change_id then return new; end if;
    if (new.change_at,new.change_id)=(old.change_at,old.change_id) then
      if (to_jsonb(new)-'change_at'-'change_id')=(to_jsonb(old)-'change_at'-'change_id') then return new; end if;
      if current_user in ('postgres','service_role','supabase_admin') then
        new.change_at:=greatest(now_ms,old.change_at+1); new.change_id:=gen_random_uuid()::text;
      else raise exception 'Mutation timestamp required'; end if;
    elsif (new.change_at,new.change_id collate "C") <= (old.change_at,old.change_id collate "C") then return null;
    end if;
  end if;
  if new.change_at<0 or new.change_at>now_ms+300000 or
    (new.change_id<>'' and new.change_id !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
  then raise exception 'Invalid mutation timestamp'; end if;
  return new;
end;
$$;
revoke all on function buylist_private.guard_change_stamp() from public,anon,authenticated;
create trigger a_change_stamp before insert or update on public.profiles for each row execute function buylist_private.guard_change_stamp();
create trigger a_change_stamp before insert or update on public.groups for each row execute function buylist_private.guard_change_stamp();
create trigger a_change_stamp before insert or update on public.group_members for each row execute function buylist_private.guard_change_stamp();
create trigger a_change_stamp before insert or update on public.shopping_lists for each row execute function buylist_private.guard_change_stamp();
commit;
