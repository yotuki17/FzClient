package dev.fzkc.fzhud.cosmetic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.integration.FzUsers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FzClient cosmetics: capes and auras.
 * <ul>
 *   <li>What you wear comes from the Cosmetics page and is published to the FzClient server, so other FzClient
 *       users draw it on you. Publishing proves who you are the same way joining a server does (Mojang's session
 *       "join" check), so nobody can change what someone else wears.</li>
 *   <li>What the other FzClient players in your lobby wear is fetched from the same server.</li>
 *   <li>Nothing is drawn on invisible players.</li>
 * </ul>
 * Rendering: capes through the vanilla cape layer ({@link #cape}), auras in {@link AuraFx}.
 */
public final class Cosmetics {
    private static final Map<String, ResourceLocation> BUILTIN = new HashMap<String, ResourceLocation>();
    static {
        for (String id : new String[]{"fzclient", "aurora", "midnight", "sakura"}) {
            BUILTIN.put(id, new ResourceLocation("fzclient", "textures/cosmetics/cape_" + id + ".png"));
        }
    }

    /** What the other FzClient players wear, by UUID. */
    private static final Map<UUID, Equip> others = new ConcurrentHashMap<UUID, Equip>();
    /** Downloaded custom capes by hash (null value = being loaded or failed). */
    private static final Map<String, ResourceLocation> customCapes = new ConcurrentHashMap<String, ResourceLocation>();
    private static final Set<String> loading = Collections.synchronizedSet(new HashSet<String>());

    // your own custom cape (config/fzclient-cape.png)
    private static ResourceLocation myCape;
    private static DynamicTexture myCapeTex;
    private static long myCapeStamp = -1, myCapeCheckAt;
    private static volatile byte[] myCapePng;
    private static volatile String myCapeError;

    private static Thread thread;

    public Cosmetics() {}

    // ------------------------------------------------------------------ who wears what

    /** What you have picked on the Cosmetics page. */
    public static Equip mine() {
        Equip e = new Equip();
        if (!Modules.cosmetics.enabled) return e;
        e.cape = Equip.CAPES[clamp(Modules.cosCape.get(), Equip.CAPES.length)];
        e.aura = Equip.AURAS[clamp(Modules.cosAura.get(), Equip.AURAS.length)];
        return e;
    }

    private static int clamp(int i, int n) {
        return i < 0 || i >= n ? 0 : i;
    }

    private static boolean isMe(EntityPlayer p) {
        Minecraft mc = Minecraft.getMinecraft();
        if (p == mc.thePlayer) return true;
        UUID me = mc.getSession() != null && mc.getSession().getProfile() != null ? mc.getSession().getProfile().getId() : null;
        return me != null && me.equals(p.getUniqueID());
    }

    /** What this player wears, or null for nothing. Never anything on invisible players. */
    public static Equip of(EntityPlayer p) {
        if (p == null || p.isInvisible()) return null;
        if (isMe(p)) {
            Equip e = mine();
            return e.empty() ? null : e;
        }
        if (!Modules.cosmetics.enabled || !Modules.cosOthers.get() || !FzUsers.uses(p.getUniqueID())) return null;
        return others.get(p.getUniqueID());
    }

    /** The cape to draw for this player, or null to keep the normal one (called from the cape mixin). */
    public static ResourceLocation cape(AbstractClientPlayer p) {
        Equip e = of(p);
        if (e == null || e.cape.isEmpty()) return null;
        if (!"custom".equals(e.cape)) return BUILTIN.get(e.cape);
        if (isMe(p)) return myCustomCape();
        if (!Modules.cosOthersCustom.get() || e.capeHash.isEmpty()) return null;
        ResourceLocation rl = customCapes.get(e.capeHash);
        if (rl == null) download(e.capeHash);
        return rl;
    }

    /** The texture of a built-in cape id, or your own custom cape for "custom" (null when there is none). */
    public static ResourceLocation capeTexture(String id) {
        return "custom".equals(id) ? myCustomCape() : BUILTIN.get(id);
    }

    /** Where your custom cape picture goes. */
    public static File myCapeFileForUser() {
        return myCapeFile();
    }

    /** Problem with your own cape file, for the settings page (null when fine). */
    public static String myCapeError() {
        return myCapeError;
    }

    // ------------------------------------------------------------------ textures

    private static File myCapeFile() {
        return new File(new File(Minecraft.getMinecraft().mcDataDir, "config"), "fzclient-cape.png");
    }

    /** Reads and checks a cape picture: PNG, twice as wide as tall, up to 1024 wide. */
    private static BufferedImage readCape(byte[] png) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null) throw new Exception("PNG を読めませんでした");
        if (img.getWidth() != img.getHeight() * 2 || img.getWidth() < 64 || img.getWidth() > 1024)
            throw new Exception("横:縦 = 2:1 の画像にしてください（64×32 など）");
        return img;
    }

    // ---- any picture works as a cape:
    //  - a normal cape texture (twice as wide as tall, 64×32 layout) is used as it is;
    //  - an OptiFine-style cape (46×22 layout) is placed into a 64×32 layout;
    //  - anything else (a photo, a logo, a square picture...) is fitted onto the back of the cape (cropped to the
    //    cape's shape, centred), with the sides and the inside in the picture's own colour.

    static BufferedImage toCape(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        if (w == h * 2 && w >= 64 && w <= 1024) return src;
        if (w * 22 == h * 46 && w >= 46) {
            int s = w / 46;
            BufferedImage out = new BufferedImage(64 * s, 32 * s, BufferedImage.TYPE_INT_ARGB);
            out.getGraphics().drawImage(src, 0, 0, null);
            return out;
        }
        int S = 8;   // 512×256: 8 pixels per cape pixel
        BufferedImage out = new BufferedImage(64 * S, 32 * S, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        java.awt.Color avg = average(src);
        java.awt.Color dark = new java.awt.Color(avg.getRed() * 6 / 10, avg.getGreen() * 6 / 10, avg.getBlue() * 6 / 10);
        // sides, top, bottom and the inside: the picture's colour
        g.setColor(dark);
        g.fillRect(0, 0, 22 * S, 17 * S);
        // back of the cape (seen from behind): the picture, cropped to 10:16 and centred
        int fx = S, fy = S, fw = 10 * S, fh = 16 * S;
        double scale = Math.max(fw / (double) w, fh / (double) h);
        int dw = (int) Math.ceil(w * scale), dh = (int) Math.ceil(h * scale);
        java.awt.Shape clip = g.getClip();
        g.setClip(fx, fy, fw, fh);
        g.drawImage(src, fx + (fw - dw) / 2, fy + (fh - dh) / 2, dw, dh, null);
        g.setClip(clip);
        g.dispose();
        return out;
    }

    private static java.awt.Color average(BufferedImage img) {
        long r = 0, gg = 0, b = 0, n = 0;
        int stepX = Math.max(1, img.getWidth() / 64), stepY = Math.max(1, img.getHeight() / 64);
        for (int y = 0; y < img.getHeight(); y += stepY) {
            for (int x = 0; x < img.getWidth(); x += stepX) {
                int c = img.getRGB(x, y);
                if (((c >>> 24) & 255) < 128) continue;
                r += (c >> 16) & 255; gg += (c >> 8) & 255; b += c & 255; n++;
            }
        }
        if (n == 0) return new java.awt.Color(20, 24, 40);
        return new java.awt.Color((int) (r / n), (int) (gg / n), (int) (b / n));
    }

    private static BufferedImage shrink(BufferedImage img, int by) {
        int w = Math.max(64, img.getWidth() / by), h = w / 2;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static byte[] encode(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** Your config/fzclient-cape.png, reloaded when the file changes. */
    private static ResourceLocation myCustomCape() {
        long now = System.currentTimeMillis();
        if (now >= myCapeCheckAt) {
            myCapeCheckAt = now + 2000L;
            File f = myCapeFile();
            long stamp = f.isFile() ? f.lastModified() ^ f.length() : 0;
            if (stamp != myCapeStamp) {
                myCapeStamp = stamp;
                myCapePng = null;
                if (myCapeTex != null) {
                    Minecraft.getMinecraft().getTextureManager().deleteTexture(myCape);
                    myCapeTex = null;
                    myCape = null;
                }
                if (stamp == 0) {
                    myCapeError = "config/fzclient-cape.png がありません";
                } else {
                    try {
                        byte[] png = Files.readAllBytes(f.toPath());
                        if (png.length > 8 * 1024 * 1024) throw new Exception("ファイルが大きすぎます（8MB まで）");
                        BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
                        if (src == null) throw new Exception("画像を読めませんでした（PNG / JPG）");
                        BufferedImage img = toCape(src);
                        byte[] out = encode(img);
                        if (out.length > 256 * 1024) { img = shrink(img, 2); out = encode(img); }
                        if (out.length > 256 * 1024) { img = shrink(img, 2); out = encode(img); }
                        myCapeTex = new DynamicTexture(img);
                        myCape = Minecraft.getMinecraft().getTextureManager().getDynamicTextureLocation("fzclient_mycape", myCapeTex);
                        myCapePng = out;   // what is uploaded: always a normal cape layout
                        myCapeError = null;
                    } catch (Exception ex) {
                        myCapeError = ex.getMessage();
                    }
                }
            }
        }
        return myCape;
    }

    /** Fetches someone's custom cape in the background; it shows up once it has arrived. */
    private static void download(final String hash) {
        if (!hash.matches("[0-9a-f]{40}") || !loading.add(hash)) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(FzUsers.ENDPOINT_URL + "/cos/cape/" + hash).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setRequestProperty("User-Agent", "FzClient/" + dev.fzkc.fzhud.feature.Branding.VERSION);
                    if (c.getResponseCode() != 200) return;
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] b = new byte[8192];
                    for (int n; (n = in.read(b)) > 0 && buf.size() < 300 * 1024; ) buf.write(b, 0, n);
                    in.close();
                    final BufferedImage img = readCape(buf.toByteArray());
                    Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            DynamicTexture tex = new DynamicTexture(img);
                            customCapes.put(hash, Minecraft.getMinecraft().getTextureManager().getDynamicTextureLocation("fzclient_cape_" + hash.substring(0, 12), tex));
                        }
                    });
                } catch (Throwable ignored) {
                    // try again later
                } finally {
                    if (!customCapes.containsKey(hash)) {
                        // allow a new try in a minute
                        final String h = hash;
                        new java.util.Timer(true).schedule(new java.util.TimerTask() {
                            @Override public void run() { loading.remove(h); }
                        }, 60000L);
                    }
                }
            }
        }, "FzClient-Cape");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ tick: layers, auras, sync

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (thread == null) {
            thread = new Thread(Cosmetics::loop, "FzClient-Cosmetics");
            thread.setDaemon(true);
            thread.start();
        }
        if (Modules.cosCape.get() == 5) myCustomCape();   // keep the file fresh for publishing
    }

    // ------------------------------------------------------------------ server

    private static void loop() {
        String sent = null;
        String changedTo = null;
        long changedAt = 0, fetchedAt = 0, failUntil = 0;
        Set<UUID> fetchedFor = new HashSet<UUID>();
        String uploadedPngHash = "";
        while (true) {
            try {
                Thread.sleep(1000L);
                long now = System.currentTimeMillis();
                Minecraft mc = Minecraft.getMinecraft();

                // ---- publish what you wear (2 s after the last change, so slider drags send once)
                Equip e = mine();
                byte[] png = "custom".equals(e.cape) ? myCapePng : null;
                if (!"custom".equals(e.cape)) uploadedPngHash = "";   // the server drops unused custom capes
                String pngHash = png == null ? "" : sha1(png);
                String want = e.toJson().toString() + "|" + pngHash;
                if (!want.equals(changedTo)) {
                    changedTo = want;
                    changedAt = now;
                }
                if (!want.equals(sent) && now - changedAt > 2000L && now > failUntil && !("custom".equals(e.cape) && png == null)) {
                    try {
                        JsonObject r = publish(mc, e, png != null && !pngHash.equals(uploadedPngHash) ? png : null);
                        if (r != null && r.has("ok")) {
                            sent = want;
                            if (png != null) uploadedPngHash = pngHash;
                        } else {
                            failUntil = now + 60000L;
                        }
                    } catch (Throwable ex) {
                        failUntil = now + 60000L;   // offline account, no internet, server down: try again later
                    }
                }

                // ---- fetch what the other FzClient players wear (new players at once, everyone each minute)
                if (!Modules.cosmetics.enabled || !Modules.cosOthers.get()) continue;
                List<UUID> ids = FzUsers.known();
                boolean fresh = false;
                for (UUID id : ids) if (!fetchedFor.contains(id)) fresh = true;
                if (ids.isEmpty() || (!fresh && now - fetchedAt < 60000L)) continue;
                JsonArray arr = new JsonArray();
                for (UUID id : ids) arr.add(new JsonPrimitive(id.toString().replace("-", "")));
                JsonObject body = new JsonObject();
                body.add("uuids", arr);
                JsonObject r = FzUsers.post("/cos/get", body);
                fetchedAt = now;
                fetchedFor = new HashSet<UUID>(ids);
                if (r == null || !r.has("cosmetics")) continue;
                JsonObject all = r.getAsJsonObject("cosmetics");
                Map<UUID, Equip> got = new HashMap<UUID, Equip>();
                for (Map.Entry<String, JsonElement> en : all.entrySet()) {
                    UUID id = uuid(en.getKey());
                    if (id != null && en.getValue().isJsonObject()) got.put(id, Equip.fromJson(en.getValue().getAsJsonObject()));
                }
                for (UUID id : ids) {
                    if (got.containsKey(id)) others.put(id, got.get(id));
                    else others.remove(id);
                }
            } catch (InterruptedException ex) {
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    /** Proves who you are (Mojang session join, like joining a server) and stores what you wear. */
    private static JsonObject publish(Minecraft mc, Equip e, byte[] png) throws Exception {
        com.mojang.authlib.GameProfile profile = mc.getSession().getProfile();
        String token = mc.getSession().getToken();
        if (profile == null || profile.getId() == null || token == null || token.length() < 20) return null;   // offline account
        String serverId = new BigInteger(130, new SecureRandom()).toString(16);
        mc.getSessionService().joinServer(profile, token, serverId);
        JsonObject body = new JsonObject();
        body.addProperty("name", profile.getName());
        body.addProperty("serverId", serverId);
        body.add("data", e.toJson());
        if (png != null) body.addProperty("cape", java.util.Base64.getEncoder().encodeToString(png));
        return FzUsers.post("/cos/set", body);
    }

    private static String sha1(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static UUID uuid(String hex) {
        if (hex == null || !hex.matches("[0-9a-f]{32}")) return null;
        return UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-"
                + hex.substring(16, 20) + "-" + hex.substring(20));
    }

}
