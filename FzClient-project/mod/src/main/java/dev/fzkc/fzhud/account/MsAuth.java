package dev.fzkc.fzhud.account;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Microsoft sign-in for Minecraft, the same steps the FzClient launcher uses:
 * device code (you type a code at microsoft.com/link) -> Xbox Live -> XSTS -> Minecraft token -> profile.
 * Every method blocks: call them from a background thread.
 */
public final class MsAuth {
    private MsAuth() {}

    /** Same Azure app as the launcher. */
    static final String CLIENT_ID = "3173158c-450f-4a3b-bb37-4ed7770c2e45";
    private static final String SCOPE = "XboxLive.signin offline_access";
    private static final String TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";

    /** A message the account screen can show as it is (Japanese source text, translated by Lang). */
    public static final class AuthError extends Exception {
        public AuthError(String msg) { super(msg); }
    }

    public static final class DeviceCode {
        public final String userCode, deviceCode, url;
        public final int interval, expiresIn;

        DeviceCode(JsonObject o) {
            userCode = o.get("user_code").getAsString();
            deviceCode = o.get("device_code").getAsString();
            url = o.has("verification_uri") ? o.get("verification_uri").getAsString() : "https://www.microsoft.com/link";
            interval = o.has("interval") ? o.get("interval").getAsInt() : 5;
            expiresIn = o.has("expires_in") ? o.get("expires_in").getAsInt() : 900;
        }
    }

    /** What a finished sign-in gives: the Minecraft session plus the Microsoft refresh token for next time. */
    public static final class Result {
        public final String name, uuid, accessToken, refreshToken;

        Result(String name, String uuid, String accessToken, String refreshToken) {
            this.name = name;
            this.uuid = uuid;
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
        }
    }

    public static DeviceCode startDeviceCode() throws Exception {
        Map<String, String> f = new LinkedHashMap<String, String>();
        f.put("client_id", CLIENT_ID);
        f.put("scope", SCOPE);
        Response r = post("https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode", form(f), true);
        if (!r.ok) throw new AuthError("Microsoft のログインを始められませんでした");
        return new DeviceCode(r.json);
    }

    public interface Cancel {
        boolean cancelled();
    }

    /** Waits until the code has been entered in the browser, then finishes the Minecraft sign-in. */
    public static Result pollDeviceCode(DeviceCode dc, Cancel cancel) throws Exception {
        long deadline = System.currentTimeMillis() + dc.expiresIn * 1000L;
        long interval = Math.max(1, dc.interval) * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (cancel != null && cancel.cancelled()) throw new AuthError("キャンセルしました");
            Thread.sleep(interval);
            if (cancel != null && cancel.cancelled()) throw new AuthError("キャンセルしました");
            Map<String, String> f = new LinkedHashMap<String, String>();
            f.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            f.put("client_id", CLIENT_ID);
            f.put("device_code", dc.deviceCode);
            Response r = post(TOKEN_URL, form(f), true);
            if (r.ok) return minecraft(r.json);
            String err = str(r.json, "error");
            if ("authorization_pending".equals(err)) continue;
            if ("slow_down".equals(err)) {
                interval += 5000;
                continue;
            }
            throw new AuthError("Microsoft のログインに失敗しました");
        }
        throw new AuthError("時間切れです。もう一度ログインしてください");
    }

    /** A fresh Minecraft token from a saved refresh token. */
    public static Result refresh(String refreshToken) throws Exception {
        Map<String, String> f = new LinkedHashMap<String, String>();
        f.put("grant_type", "refresh_token");
        f.put("client_id", CLIENT_ID);
        f.put("refresh_token", refreshToken);
        f.put("scope", SCOPE);
        Response r = post(TOKEN_URL, form(f), true);
        if (!r.ok) throw new AuthError("もう一度ログインしてください");
        return minecraft(r.json);
    }

    private static Result minecraft(JsonObject msa) throws Exception {
        JsonObject props = new JsonObject();
        props.addProperty("AuthMethod", "RPS");
        props.addProperty("SiteName", "user.auth.xboxlive.com");
        props.addProperty("RpsTicket", "d=" + msa.get("access_token").getAsString());
        JsonObject body = new JsonObject();
        body.add("Properties", props);
        body.addProperty("RelyingParty", "http://auth.xboxlive.com");
        body.addProperty("TokenType", "JWT");
        Response xbl = post("https://user.auth.xboxlive.com/user/authenticate", body.toString(), false);
        if (!xbl.ok) throw new AuthError("Xbox Live のログインに失敗しました");
        String xblToken = xbl.json.get("Token").getAsString();
        String uhs = xbl.json.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();

        JsonObject xp = new JsonObject();
        xp.addProperty("SandboxId", "RETAIL");
        JsonArray ut = new JsonArray();
        ut.add(new com.google.gson.JsonPrimitive(xblToken));
        xp.add("UserTokens", ut);
        JsonObject xb = new JsonObject();
        xb.add("Properties", xp);
        xb.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        xb.addProperty("TokenType", "JWT");
        Response xsts = post("https://xsts.auth.xboxlive.com/xsts/authorize", xb.toString(), false);
        if (!xsts.ok) {
            long code = xsts.json.has("XErr") ? xsts.json.get("XErr").getAsLong() : 0;
            if (code == 2148916233L) throw new AuthError("この Microsoft アカウントには Xbox のプロフィールがありません");
            if (code == 2148916238L) throw new AuthError("18歳未満のアカウントはファミリーに追加が必要です");
            throw new AuthError("XSTS のログインに失敗しました");
        }

        JsonObject mb = new JsonObject();
        mb.addProperty("identityToken", "XBL3.0 x=" + uhs + ";" + xsts.json.get("Token").getAsString());
        Response mc = post("https://api.minecraftservices.com/authentication/login_with_xbox", mb.toString(), false);
        if (!mc.ok) {
            throw new AuthError(mc.status == 403 ? "このアプリは Minecraft のログイン許可がまだありません" : "Minecraft のログインに失敗しました");
        }
        String token = mc.json.get("access_token").getAsString();

        HttpURLConnection c = open("https://api.minecraftservices.com/minecraft/profile");
        c.setRequestProperty("Authorization", "Bearer " + token);
        Response p = read(c);
        if (!p.ok || !p.json.has("id")) throw new AuthError("このアカウントは Minecraft Java 版を持っていません");
        String refresh = msa.has("refresh_token") ? msa.get("refresh_token").getAsString() : null;
        return new Result(p.json.get("name").getAsString(), p.json.get("id").getAsString(), token, refresh);
    }

    // ------------------------------------------------------------------ http

    private static final class Response {
        boolean ok;
        int status;
        JsonObject json = new JsonObject();
    }

    private static String form(Map<String, String> f) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : f.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        return sb.toString();
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "FzClient/1.0");
        c.setRequestProperty("Accept", "application/json");
        return c;
    }

    private static Response post(String url, String body, boolean isForm) throws Exception {
        HttpURLConnection c = open(url);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", isForm ? "application/x-www-form-urlencoded" : "application/json");
        OutputStream out = c.getOutputStream();
        try {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
        return read(c);
    }

    private static Response read(HttpURLConnection c) throws Exception {
        Response r = new Response();
        r.status = c.getResponseCode();
        r.ok = r.status >= 200 && r.status < 300;
        InputStream in = r.ok ? c.getInputStream() : c.getErrorStream();
        if (in != null) {
            try {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
                String text = new String(buf.toByteArray(), StandardCharsets.UTF_8).trim();
                if (text.startsWith("{")) r.json = new JsonParser().parse(text).getAsJsonObject();
            } catch (Exception ignored) {
            } finally {
                in.close();
            }
        }
        c.disconnect();
        return r;
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }
}
