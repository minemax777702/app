# BuyList — Supabase

Stable 1.2.0 is the baseline. GitHub remains only the source/release host. No SSH, deploy keys, invitation files, QR enrollment or nearby transport.

## Backend setup
Project [buylist](https://supabase.com/dashboard/project/fgklaqxwwfhjukxkxvja) is deployed in eu-central-1. The initial schema is applied. RLS and Realtime publication verified; security advisors report no findings. Database checks of registration trigger, private list isolation, group CRUD/membership, optimistic revision update, membership revocation, role protection and user disabling passed in a rolled-back transaction.
The public client configuration is supplied in gradle.properties; optional nonempty CI variables override it. Never place a service-role or secret key in the APK.
Register with a confirmed email, then grant the initial admin role in the dashboard/SQL to the verified account ID: `update public.profiles set role='admin' where id='<verified-user-uuid>';`. Configure email delivery for production if needed.
Signed beta and device-to-device authentication, Realtime and offline behavior still need verification.

## Storage
Five tables: profiles, groups, group_members, shopping_lists, list_states. Group owners manage membership directly; group members can edit purchases; global admins manage account roles/status and groups but cannot implicitly read private purchases. Delete archives list/group metadata, retaining existing purchase content. Users are disabled instead of deleting auth identity and purchase attribution.
Supabase Auth persists sessions. Each account/list has its own local Room DB; the active list is restored from preferences. Postgres Changes on metadata wakes the sync loop; periodic refresh catches missed notifications. State updates use an optimistic revision check, and the existing stable deterministic purchase/archival merge. This is document-level synchronization, not individual SQL CRUD for product rows. Local backups remain usable. Custom product photo binaries are local only in this implementation. Existing local purchases are not uploaded until explicitly imported into a personal list.

## Verification
- Sign up, confirm email, sign in and restart.
- Create/edit/archive personal lists and groups, add/remove a registered participant, disable a user.
- RLS: two users cannot read or modify each other's private lists; client cannot self-promote; removed/disabled users lose remote access.
- Both phones: simultaneous add/buy/delete, archive and price/store edits, airplane mode and reconnection, restart with selected list, switch account, verify offline caches and widget.
- Validate release APK and Auth session refresh. No server credentials in APK.

## Email confirmation
In Supabase Auth URL Configuration, set the Site URL and add the exact redirect URL `com.mok.buylist://auth-confirm` (no wildcard). Android requests this redirect with PKCE and exchanges the one-time code. Existing confirmed accounts can sign in normally. If the link was opened on another device, sign in with email/password. The dashboard setting is not managed by the available Supabase connector.

Cloud diagnostics: `adb logcat -d -s BuyListCloud AndroidRuntime` (do not share credentials or auth callback URLs).

## Release compatibility
PostgREST responses are decoded directly as JSON arrays; CRUD sends concrete JSON rather than reflective generic serialization. Kotlin runtime resources are retained. Beta builds are signed but not minified; stable retains optimization. Realtime errors do not block REST access.

## Groups and offline CRUD
A group is a container, not a list. The deployed migration permits any active participant to create, rename, archive and edit shared lists, but group membership remains owner/admin managed. Admins cannot read private or shared shopping content without membership. Existing data is retained. Offline-created groups/lists use client UUIDs and persistent account-specific pending mutations; passwords are never queued. List selection opens Room immediately. Existing guest purchases are merged into the first authenticated account's personal list and bound to that account; the original guest database is preserved. Account creation, authentication and changing account roles require connectivity. The manage-users Edge Function creates accounts only after verifying a confirmed active administrator and sends email confirmation; the first admin must still be assigned via the dashboard.

The offline personal list uses the same logical name and stable server mapping as the account personal list. It is merged before switching workspaces, not exposed as an additional "На телефоне" list. The original local database is retained for recovery; an explicit sign-out does not expose another account’s private cloud cache. Previously linked local data is never silently transferred to another account.
