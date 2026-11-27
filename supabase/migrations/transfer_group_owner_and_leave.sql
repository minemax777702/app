begin;
create function buylist_private.transfer_and_leave(p_group uuid, p_successor uuid, p_at bigint, p_op text)
returns boolean language plpgsql security definer set search_path = '' as $$
declare uid uuid := auth.uid(); g public.groups%rowtype; n integer;
begin
  if uid is null or not buylist_private.enabled() or p_successor is null or p_successor=uid
     or p_at is null or p_op is null or p_op !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
    raise exception 'Invalid group departure' using errcode='42501';
  end if;
  select * into g from public.groups where id=p_group for update;
  if not found or g.archived or g.owner_id<>uid then raise exception 'Owner required' using errcode='42501'; end if;
  perform 1 from public.group_members m join public.profiles p on p.id=m.user_id
    where m.group_id=p_group and m.user_id=p_successor and m.active and p.active for update of m,p;
  if not found then raise exception 'Successor must be an active member' using errcode='42501'; end if;
  update public.groups set owner_id=p_successor,change_at=p_at,change_id=p_op where id=p_group;
  get diagnostics n=row_count;
  if n<>1 then raise exception 'Newer group change exists' using errcode='40001'; end if;
  if exists(select 1 from public.group_members where group_id=p_group and user_id=uid and active) then
    update public.group_members set active=false,change_at=p_at,change_id=p_op where group_id=p_group and user_id=uid and active;
    get diagnostics n=row_count;
    if n<>1 then raise exception 'Newer membership change exists' using errcode='40001'; end if;
  end if;
  return true;
end $$;
revoke all on function buylist_private.transfer_and_leave(uuid,uuid,bigint,text) from public,anon,authenticated;
grant execute on function buylist_private.transfer_and_leave(uuid,uuid,bigint,text) to authenticated;
create function public.transfer_and_leave(p_group uuid,p_successor uuid,p_at bigint,p_op text)
returns boolean language sql security invoker set search_path = '' as $$
select buylist_private.transfer_and_leave(p_group,p_successor,p_at,p_op)
$$;
revoke all on function public.transfer_and_leave(uuid,uuid,bigint,text) from public,anon,authenticated;
grant execute on function public.transfer_and_leave(uuid,uuid,bigint,text) to authenticated;
commit;
