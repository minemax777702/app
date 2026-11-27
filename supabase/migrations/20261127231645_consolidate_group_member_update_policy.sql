alter policy members_edit on public.group_members
  using (buylist_private.group_owner(group_id) or buylist_private.is_admin() or (buylist_private.enabled() and user_id=(select auth.uid()) and active))
  with check (buylist_private.group_owner(group_id) or buylist_private.is_admin() or (buylist_private.enabled() and user_id=(select auth.uid()) and not active));
drop policy members_leave_self on public.group_members;
