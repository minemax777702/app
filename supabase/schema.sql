-- BuyList: apply once to a new Supabase project. All client-accessible data is RLS protected.
begin;
create schema if not exists buylist_private;
revoke all on schema buylist_private from public, anon, authenticated;
grant usage on schema buylist_private to authenticated;

create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  display_name text not null check (char_length(trim(display_name)) between 1 and 50),
  role text not null default 'user' check (role in ('admin','user')),
  active boolean not null default true
);
create table public.groups (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null default auth.uid() references public.profiles(id) on delete restrict,
  name text not null check (char_length(trim(name)) between 1 and 100),
  archived boolean not null default false
);
create table public.group_members (
  group_id uuid not null references public.groups(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  active boolean not null default true,
  primary key (group_id,user_id)
);
create index group_members_user_idx on public.group_members(user_id,group_id) where active;
create table public.shopping_lists (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid references public.profiles(id) on delete restrict,
  group_id uuid references public.groups(id) on delete restrict,
  name text not null check (char_length(trim(name)) between 1 and 100),
  archived boolean not null default false,
  revision bigint not null default 0,
  check ((owner_id is null) <> (group_id is null))
);
create index shopping_lists_owner_idx on public.shopping_lists(owner_id);
create index shopping_lists_group_idx on public.shopping_lists(group_id);
create table public.list_states (
  list_id uuid primary key references public.shopping_lists(id) on delete cascade,
  revision bigint not null default 0 check (revision >= 0),
  payload text not null default '' check (octet_length(payload) <= 8388608)
);

create function buylist_private.enabled() returns boolean language sql stable security definer set search_path = '' as $$
  select exists(select 1 from public.profiles p where p.id = (select auth.uid()) and p.active);
$$;
create function buylist_private.is_admin() returns boolean language sql stable security definer set search_path = '' as $$
  select exists(select 1 from public.profiles p where p.id = (select auth.uid()) and p.active and p.role = 'admin');
$$;
create function buylist_private.group_owner(g uuid) returns boolean language sql stable security definer set search_path = '' as $$
  select buylist_private.enabled() and exists(select 1 from public.groups x where x.id=g and x.owner_id=(select auth.uid()) and not x.archived);
$$;
create function buylist_private.group_access(g uuid) returns boolean language sql stable security definer set search_path = '' as $$
  select buylist_private.enabled() and exists(select 1 from public.groups x where x.id=g and not x.archived and
    (x.owner_id=(select auth.uid()) or exists(select 1 from public.group_members m where m.group_id=g and m.user_id=(select auth.uid()) and m.active)));
$$;
create function buylist_private.list_access(l uuid) returns boolean language sql stable security definer set search_path = '' as $$
  select buylist_private.enabled() and exists(select 1 from public.shopping_lists x where x.id=l and not x.archived and
    (x.owner_id=(select auth.uid()) or buylist_private.group_access(x.group_id)));
$$;
revoke all on all functions in schema buylist_private from public, anon, authenticated;
grant execute on function buylist_private.enabled(), buylist_private.is_admin(), buylist_private.group_owner(uuid), buylist_private.group_access(uuid), buylist_private.list_access(uuid) to authenticated;

alter table public.profiles enable row level security;
alter table public.groups enable row level security;
alter table public.group_members enable row level security;
alter table public.shopping_lists enable row level security;
alter table public.list_states enable row level security;
revoke all on public.profiles,public.groups,public.group_members,public.shopping_lists,public.list_states from anon,authenticated;
grant select on public.profiles,public.groups,public.group_members,public.shopping_lists,public.list_states to authenticated;
grant update(display_name,role,active) on public.profiles to authenticated;
grant insert(name) on public.groups to authenticated;
grant update(name,archived) on public.groups to authenticated;
grant insert(group_id,user_id,active),update(active) on public.group_members to authenticated;
grant insert(owner_id,group_id,name),update(name,archived) on public.shopping_lists to authenticated;
grant update(revision,payload) on public.list_states to authenticated;

create policy profiles_read on public.profiles for select to authenticated using (
  id=(select auth.uid()) or (buylist_private.enabled() and (active or buylist_private.is_admin())));
create policy profiles_edit on public.profiles for update to authenticated
  using (buylist_private.enabled() and (id=(select auth.uid()) or buylist_private.is_admin()))
  with check (id=(select auth.uid()) or buylist_private.is_admin());
create policy groups_read on public.groups for select to authenticated using (
  buylist_private.enabled() and (owner_id=(select auth.uid()) or buylist_private.group_access(id) or buylist_private.is_admin()));
create policy groups_add on public.groups for insert to authenticated with check (buylist_private.enabled() and owner_id=(select auth.uid()));
create policy groups_edit on public.groups for update to authenticated
  using (buylist_private.group_owner(id) or buylist_private.is_admin())
  with check (buylist_private.enabled() and (owner_id=(select auth.uid()) or buylist_private.is_admin()));
create policy members_read on public.group_members for select to authenticated using (
  buylist_private.enabled() and (user_id=(select auth.uid()) or buylist_private.group_access(group_id) or buylist_private.is_admin()));
create policy members_add on public.group_members for insert to authenticated
  with check ((buylist_private.group_owner(group_id) or buylist_private.is_admin()) and
    exists(select 1 from public.profiles p where p.id=user_id and p.active));
create policy members_edit on public.group_members for update to authenticated
  using (buylist_private.group_owner(group_id) or buylist_private.is_admin())
  with check (buylist_private.group_owner(group_id) or buylist_private.is_admin());
-- Admin does not automatically receive access to private shopping contents.
create policy lists_read on public.shopping_lists for select to authenticated using (
  buylist_private.enabled() and (owner_id=(select auth.uid()) or buylist_private.group_access(group_id)));
create policy lists_add on public.shopping_lists for insert to authenticated with check (
  buylist_private.enabled() and (owner_id=(select auth.uid()) or buylist_private.group_owner(group_id)));
create policy lists_edit on public.shopping_lists for update to authenticated
  using (buylist_private.enabled() and (owner_id=(select auth.uid()) or buylist_private.group_owner(group_id)))
  with check (buylist_private.enabled() and (owner_id=(select auth.uid()) or buylist_private.group_owner(group_id)));
create policy states_read on public.list_states for select to authenticated using (buylist_private.list_access(list_id));
create policy states_write on public.list_states for update to authenticated
  using (buylist_private.list_access(list_id)) with check (buylist_private.list_access(list_id));

create function buylist_private.guard_profile() returns trigger language plpgsql set search_path = '' as $$
begin
  if (new.role,new.active) is distinct from (old.role,old.active) and (select auth.uid()) is not null then
    if not buylist_private.is_admin() or old.id=(select auth.uid()) then
      raise exception 'Not allowed to change role or status';
    end if;
  end if;
  return new;
end;
$$;
create trigger guard_profile before update on public.profiles for each row execute function buylist_private.guard_profile();

create function buylist_private.create_state() returns trigger language plpgsql security definer set search_path = '' as $$
begin
  insert into public.list_states(list_id) values(new.id);
  return new;
end;
$$;
create trigger create_state after insert on public.shopping_lists for each row execute function buylist_private.create_state();

create function buylist_private.register_profile() returns trigger language plpgsql security definer set search_path = '' as $$
begin
  insert into public.profiles(id,display_name)
    values(new.id,coalesce(nullif(left(trim(new.raw_user_meta_data->>'display_name'),50),''),'Пользователь'));
  insert into public.shopping_lists(owner_id,name) values(new.id,'Личный список');
  return new;
end;
$$;
create trigger buylist_register_profile after insert on auth.users for each row execute function buylist_private.register_profile();

create function buylist_private.create_group_list() returns trigger language plpgsql security definer set search_path = '' as $$
begin
  insert into public.shopping_lists(group_id,name) values(new.id,new.name);
  return new;
end;
$$;
create trigger create_group_list after insert on public.groups for each row execute function buylist_private.create_group_list();

create function buylist_private.advance_revision() returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if new.revision <> old.revision + 1 then raise exception 'Invalid revision'; end if;
  update public.shopping_lists set revision=new.revision where id=new.list_id;
  return new;
end;
$$;
create trigger advance_revision after update on public.list_states for each row execute function buylist_private.advance_revision();
revoke all on function buylist_private.guard_profile(),buylist_private.create_state(),buylist_private.register_profile(),buylist_private.create_group_list(),buylist_private.advance_revision() from public,anon,authenticated;
-- Publish only small metadata rows, not the shopping payload. No DELETE events are needed.
alter publication supabase_realtime add table public.profiles, public.groups, public.group_members, public.shopping_lists;
commit;
