begin;
create policy members_leave_self on public.group_members for update to authenticated
using (buylist_private.enabled() and user_id=(select auth.uid()) and active)
with check (buylist_private.enabled() and user_id=(select auth.uid()) and not active);
commit;
