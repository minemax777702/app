-- Applied to buylist. Retain all existing lists.
begin;
drop trigger if exists create_group_list on public.groups;
drop policy lists_add on public.shopping_lists;
drop policy lists_edit on public.shopping_lists;
create policy lists_add on public.shopping_lists for insert to authenticated with check (
  buylist_private.enabled() and (owner_id=(select auth.uid()) or buylist_private.group_access(group_id)));
create policy lists_edit on public.shopping_lists for update to authenticated
  using (buylist_private.enabled() and (owner_id=(select auth.uid()) or buylist_private.group_access(group_id)))
  with check (buylist_private.enabled() and (owner_id=(select auth.uid()) or buylist_private.group_access(group_id)));
grant insert(id) on public.groups, public.shopping_lists to authenticated;
commit;
