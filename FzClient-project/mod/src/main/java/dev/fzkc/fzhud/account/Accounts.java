package dev.fzkc.fzhud.account;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Session;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Accounts you can switch to from the title screen. Microsoft accounts keep only their refresh token (encrypted
 * with Windows' per-user encryption, see Protect) in .minecraft/config/fzclient-accounts.json; the Minecraft
 * token is fetched again on every switch. The account the game was started with (from the launcher) is always
 * listed too, as long as the game runs.
 */
public final class Accounts {
    private Accounts() {}

    public static final class Account {
        public final String type;   // "msa" or "offline"
        public String name;
        public final String uuid;   // without dashes
        String refreshSealed;       // msa only

        Account(String type, String name, String uuid) {
            this.type = type;
            this.name = name;
            this.uuid = uuid.replace("-", "");
        }

        public boolean microsoft() { return "msa".equals(type); }
    }

    private static final List<Account> list = new ArrayList<Account>();
    private static boolean loaded;
    /** The session the launcher started the game with. */
    private static Session launch;

    /** Remembers the launcher's session (call once at start-up, before anything switches). */
    public static void init() {
        if (launch == null) launch = Minecraft.getMinecraft().getSession();
    }

    public static Session launchSession() {
        init();
        return launch;
    }

    public static synchronized List<Account> saved() {
        load();
        return new ArrayList<Account>(list);
    }

    // ------------------------------------------------------------------ switching

    /** Puts a session into the running game (servers you join from now on see this account). */
    public static void apply(Session s) {
        ReflectionHelper.setPrivateValue(Minecraft.class, Minecraft.getMinecraft(), s, "session", "field_71449_j");
    }

    /** Session for an offline account: the same UUID Minecraft itself gives offline players. */
    public static Session offlineSession(String name) {
        UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        return new Session(name, id.toString().replace("-", ""), "0", "legacy");
    }

    /** Signs a saved account in again (blocks: Microsoft accounts go to the network). */
    public static Session sessionFor(Account a) throws Exception {
        if (!a.microsoft()) return offlineSession(a.name);
        String refresh = Protect.open(a.refreshSealed);
        if (refresh == null) throw new MsAuth.AuthError("もう一度ログインしてください");
        MsAuth.Result r = MsAuth.refresh(refresh);
        synchronized (Accounts.class) {
            a.name = r.name;
            if (r.refreshToken != null) a.refreshSealed = Protect.seal(r.refreshToken);
            save();
        }
        return new Session(r.name, r.uuid, r.accessToken, "mojang");
    }

    // ------------------------------------------------------------------ adding / removing

    /** Saves a finished Microsoft sign-in and returns its session. */
    public static synchronized Session addMicrosoft(MsAuth.Result r) {
        load();
        Account a = find(r.uuid);
        if (a == null || !a.microsoft()) {
            if (a != null) list.remove(a);
            a = new Account("msa", r.name, r.uuid);
            list.add(a);
        }
        a.name = r.name;
        a.refreshSealed = Protect.seal(r.refreshToken);
        save();
        return new Session(r.name, r.uuid, r.accessToken, "mojang");
    }

    public static synchronized Session addOffline(String name) {
        load();
        Session s = offlineSession(name);
        if (find(s.getPlayerID()) == null) {
            list.add(new Account("offline", name, s.getPlayerID()));
            save();
        }
        return s;
    }

    public static synchronized void remove(Account a) {
        load();
        list.remove(a);
        save();
    }

    private static Account find(String uuid) {
        String u = uuid.replace("-", "");
        for (Account a : list) if (a.uuid.equalsIgnoreCase(u)) return a;
        return null;
    }

    // ------------------------------------------------------------------ file

    private static File file() {
        return new File(new File(Minecraft.getMinecraft().mcDataDir, "config"), "fzclient-accounts.json");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        File f = file();
        if (!f.isFile()) return;
        Reader r = null;
        try {
            r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8);
            JsonObject root = new JsonParser().parse(r).getAsJsonObject();
            for (JsonElement e : root.getAsJsonArray("accounts")) {
                JsonObject o = e.getAsJsonObject();
                Account a = new Account(o.get("type").getAsString(), o.get("name").getAsString(), o.get("uuid").getAsString());
                if (o.has("refresh")) a.refreshSealed = o.get("refresh").getAsString();
                list.add(a);
            }
        } catch (Exception e) {
            System.err.println("[FzClient] Could not read accounts: " + e);
        } finally {
            try { if (r != null) r.close(); } catch (Exception ignored) { }
        }
    }

    private static void save() {
        JsonArray arr = new JsonArray();
        for (Account a : list) {
            JsonObject o = new JsonObject();
            o.addProperty("type", a.type);
            o.addProperty("name", a.name);
            o.addProperty("uuid", a.uuid);
            if (a.refreshSealed != null) o.addProperty("refresh", a.refreshSealed);
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("accounts", arr);
        File f = file();
        f.getParentFile().mkdirs();
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try {
            OutputStream out = new FileOutputStream(tmp);
            try {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            if (f.exists() && !f.delete()) throw new java.io.IOException("cannot replace " + f);
            if (!tmp.renameTo(f)) throw new java.io.IOException("cannot write " + f);
        } catch (Exception e) {
            System.err.println("[FzClient] Could not save accounts: " + e);
        }
    }
}
