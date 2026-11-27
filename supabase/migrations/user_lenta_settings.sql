begin;
create table public.user_lenta_settings (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  address text not null default '' check (char_length(address) <= 500),
  change_at bigint not null,
  change_id text not null
);
alter table public.user_lenta_settings enable row level security;
revoke all on public.user_lenta_settings from public,anon,authenticated;
grant select on public.user_lenta_settings to authenticated;
grant insert(user_id,address,change_at,change_id), update(address,change_at,change_id) on public.user_lenta_settings to authenticated;
create policy lenta_settings_read on public.user_lenta_settings for select to authenticated
using (buylist_private.enabled() and user_id=(select auth.uid()));
create policy lenta_settings_insert on public.user_lenta_settings for insert to authenticated
with check (buylist_private.enabled() and user_id=(select auth.uid()));
create policy lenta_settings_update on public.user_lenta_settings for update to authenticated
using (buylist_private.enabled() and user_id=(select auth.uid()))
with check (buylist_private.enabled() and user_id=(select auth.uid()));
create trigger a_change_stamp before insert or update on public.user_lenta_settings for each row execute function buylist_private.guard_change_stamp();
alter publication supabase_realtime add table public.user_lenta_settings;
commit;
