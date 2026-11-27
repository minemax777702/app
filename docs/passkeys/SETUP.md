# Native Android passkeys

Uses official Supabase Auth passkey registration and authentication REST endpoints with Android Credential Manager. No password or private key is stored by BuyList. Sign-in imports the server-issued UserSession into supabase-kt; existing UUID, roles and lists remain unchanged. Password sign-in remains available. No automatic locking.

## Required deployment configuration

The available connector cannot change hosted Auth configuration or configure a domain. Until these are configured, the app handles passkey-disabled responses and password login remains available.

1. Choose a dedicated HTTPS domain that you control for BuyList's relying party ID. Do not change the RP ID once keys have been registered.
2. Serve the accompanying `assetlinks.json` at `https://YOUR-RP-DOMAIN/.well-known/assetlinks.json`, with HTTP 200, application/json and no redirect. It contains the SHA-256 fingerprint of the actual CI Release signing certificate. Verify that the file is publicly reachable.
3. In Supabase Dashboard → Authentication → Passkeys, enable Passkey authentication. RP name: BuyList. RP ID: YOUR-RP-DOMAIN. Configure only the intended origins. Native Android origin: `android:apk-key-hash:iEhkYQYhzcOKvf1hqxc3-GaWUtvx_RlRmhKQQHl67a8`. If the hosted dashboard rejects native origins, do not weaken origin checks; a browser-based implementation on a controlled HTTPS site will be required instead.
4. Register a key after a normal sign-in and verify sign-out/sign-in on a signed Release APK. Test a second device using a synced provider. Check revocation and fallback to password.

References: https://supabase.com/docs/guides/auth/passkeys and https://developer.android.com/identity/credential-manager/prerequisites.
The hosted API is experimental. No real-device registration or login has been verified.
