package dev.fzkc.fzhud.stats;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Optional;

/**
 * Where you are on Hypixel from the official Hypixel Mod API (when that mod is installed): the server sends a
 * location packet on every server change, so /locraw is not needed. Used through reflection, so FzClient still
 * works without the mod.
 */
final class ModApiLocation {
    private ModApiLocation() {}

    private static final Logger LOG = LogManager.getLogger("FzClient-Stats");
    static volatile boolean available;
    /** The latest location: game type ("BEDWARS"), mode, lobby name, and when it arrived (0 = none yet). */
    static volatile String type, mode, lobbyName, map;
    static volatile long at;

    static void init() {
        try {
            Class<?> api = Class.forName("net.hypixel.modapi.HypixelModAPI");
            Class<?> packet = Class.forName("net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket");
            Class<?> handler = Class.forName("net.hypixel.modapi.handler.ClientboundPacketHandler");
            Object inst = api.getMethod("getInstance").invoke(null);
            Method serverType = packet.getMethod("getServerType"), getMode = packet.getMethod("getMode"), getLobby = packet.getMethod("getLobbyName");
            Method getMap = packet.getMethod("getMap");
            Object h = Proxy.newProxyInstance(handler.getClassLoader(), new Class<?>[]{handler}, (proxy, m, args) -> {
                if (m.getDeclaringClass() == Object.class) {
                    switch (m.getName()) {
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        default: return "FzClient location handler";
                    }
                }
                if (!"handle".equals(m.getName()) || args == null || args.length != 1) return null;
                try {
                    Object p = args[0];
                    Optional<?> st = (Optional<?>) serverType.invoke(p);
                    Optional<?> md = (Optional<?>) getMode.invoke(p);
                    Optional<?> lb = (Optional<?>) getLobby.invoke(p);
                    String t = null;
                    if (st.isPresent()) t = (String) st.get().getClass().getMethod("name").invoke(st.get());
                    type = t;
                    mode = md.isPresent() ? String.valueOf(md.get()) : null;
                    lobbyName = lb.isPresent() ? String.valueOf(lb.get()) : null;
                    Optional<?> mp = (Optional<?>) getMap.invoke(p);
                    map = mp.isPresent() ? String.valueOf(mp.get()) : null;
                    at = System.currentTimeMillis();
                } catch (Throwable e) {
                }
                return null;
            });
            api.getMethod("createHandler", Class.class, handler).invoke(inst, packet, h);
            api.getMethod("subscribeToEventPacket", Class.class).invoke(inst, packet);
            available = true;
        } catch (ClassNotFoundException e) {
            // the Hypixel Mod API is not installed: /locraw only
        } catch (Throwable e) {
        }
    }
}
