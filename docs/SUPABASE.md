# BuyList — Supabase

Stable 1.2.0 is the baseline. GitHub remains only the source/release host. No SSH, deploy keys, invitation files, QR enrollment or nearby transport.

## Backend setup
1. Create a dedicated Supabase project in the BuyList organization.
2. Apply `supabase/schema.sql` once in SQL Editor or via a migration. Run security advisors and RLS tests.
3. Keep email confirmation enabled; configure email delivery before onboarding. Users register using name, email and password. The personal list is created automatically. Grant the first admin role only through the dashboard/SQL, using a verified account ID: `update public.profiles set role='admin' where id='<verified-user-uuid>';`.
4. Set GitHub repository Actions variables SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY (only the public publishable key, never a service-role key). Local builds accept the same Gradle properties. No runtime setup form.
5. Build a signed beta and verify with two distinct users/phones.

## Storage
Five tables: profiles, groups, group_members, shopping_lists, list_states. Group owners manage membership directly; group members can edit purchases; global admins manage account roles/status and groups but cannot implicitly read private purchases. Delete archives list/group metadata, retaining existing purchase content. Users are disabled instead of deleting auth identity and purchase attribution.
Supabase Auth persists sessions. Each account/list has its own local Room DB; the active list is restored from preferences. Postgres Changes on metadata wakes the sync loop; periodic refresh catches missed notifications. State updates use an optimistic revision check, and the existing stable deterministic purchase/archival merge. This is document-level synchronization, not individual SQL CRUD for product rows. Local backups remain usable. Custom product photo binaries are local only in this implementation. Existing local purchases are not uploaded until explicitly imported into a personal list.

## Verification
- Sign up, confirm email, sign in and restart.
- Create/edit/archive personal lists and groups, add/remove a registered participant, disable a user.
- RLS: two users cannot read or modify each other's private lists; client cannot self-promote; removed/disabled users lose remote access.
- Both phones: simultaneous add/buy/delete, archive and price/store edits, airplane mode and reconnection, restart with selected list, switch account, verify offline caches and widget.
- Validate release APK and Auth session refresh. No server credentials in APK.
