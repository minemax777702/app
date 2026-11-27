import { createClient } from "npm:@supabase/supabase-js@2.57.4";

const reply = (status: number, data: Record<string, unknown>) =>
  Response.json(data, { status, headers: { "Cache-Control": "no-store" } });

Deno.serve(async (req) => {
  if (req.method !== "POST") return reply(405, { error: "method" });
  try {
    const token = req.headers.get("Authorization")?.match(/^Bearer ([^\s]+)$/i)?.[1];
    if (!token) return reply(401, { error: "auth" });
    const url = Deno.env.get("SUPABASE_URL")!;
    const secret = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const admin = createClient(url, secret, { auth: { persistSession: false, autoRefreshToken: false } });
    const { data: auth, error: authError } = await admin.auth.getUser(token);
    if (authError || !auth.user?.id || !auth.user.email_confirmed_at) return reply(401, { error: "auth" });
    const { data: profile, error: profileError } = await admin.from("profiles")
      .select("role,active").eq("id", auth.user.id).single();
    if (profileError || profile?.role !== "admin" || profile.active !== true)
      return reply(403, { error: "forbidden" });
    const body = await req.json();
    const email = typeof body.email === "string" ? body.email.trim().toLowerCase() : "";
    const name = typeof body.name === "string" ? body.name.trim() : "";
    const password = typeof body.password === "string" ? body.password : "";
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 254 ||
      name.length < 1 || name.length > 50 || password.length < 8 || password.length > 128)
      return reply(400, { error: "validation" });
    const { data, error } = await admin.auth.admin.createUser({
      email, password, email_confirm: false, user_metadata: { display_name: name }
    });
    if (error || !data.user) return reply(400, { error: "create_user" });
    // Do not mark someone else's address verified. Send the standard signup confirmation.
    const publicClient = createClient(url, Deno.env.get("SUPABASE_ANON_KEY")!, {
      auth: { persistSession: false, autoRefreshToken: false }
    });
    const { error: mailError } = await publicClient.auth.resend({
      type: "signup", email, options: { emailRedirectTo: "com.mok.buylist://auth-confirm" }
    });
    return reply(201, { id: data.user.id, confirmation_sent: !mailError });
  } catch (_) {
    return reply(500, { error: "server" });
  }
});
