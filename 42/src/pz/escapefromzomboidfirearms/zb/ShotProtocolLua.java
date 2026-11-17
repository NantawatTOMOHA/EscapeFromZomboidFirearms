package pz.escapefromzomboidfirearms.zb;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.UUID;
import me.zed_0xff.zombie_buddy.Exposer;

/** Thin optional Lua transport boundary. Does not load VP, PZ, GL or an InventoryItem.
 * A/B integration installs actual peers and the trusted native handshake factory.
 * Until then ready() is false; a Lua Fire/Hello cannot create an authority adapter.
 */
@Exposer.LuaClass
public final class ShotProtocolLua {
    public interface HelloFactory { ShotProtocol.Hello open(int shooter, String challenge) throws Exception; }
    private static volatile ShotProtocol server, client;
    private static volatile HelloFactory helloFactory;
    private static final ArrayDeque<String> outbound = new ArrayDeque<>();
    private static volatile String error = "server-adapter-unavailable";
    private ShotProtocolLua() { }

    public static synchronized void installServer(ShotProtocol peer, HelloFactory factory) {
        if (peer.role() != ShotProtocol.Role.SERVER) throw new IllegalArgumentException("Server peer");
        server = peer; helloFactory = Objects.requireNonNull(factory);
    }
    public static synchronized void installClient(ShotProtocol peer) {
        if (peer.role() != ShotProtocol.Role.CLIENT && peer.role() != ShotProtocol.Role.OBSERVER) throw new IllegalArgumentException("Client peer");
        client = peer;
    }
    /** Use as the installed peer's Transport. -1=server, -2=observers, >=0=one player. */
    public static synchronized void send(int recipient, ShotProtocol.Message message) throws Exception {
        if (outbound.size() >= 4096) throw new IllegalStateException("Shot transport capacity");
        outbound.addLast(recipient + "|" + ShotProtocol.wire(message));
    }
    public static boolean ready(boolean onServer) { ShotProtocol peer=onServer?server:client; return peer!=null && peer.ready(true); }
    public static String status() {
        ShotProtocol c=client,s=server;
        return "client=" + (c == null ? "absent" : c.status()) + " server=" + (s == null ? "absent" : s.status()) + " reason=" + error;
    }
    public static boolean clientChallenge(String value) {
        ShotProtocol peer=client;if (peer == null) return false;
        peer.challenge(value); return true;
    }
    public static String hello(int sender, String challenge) {
        HelloFactory factory=helloFactory;
        if (!ready(true) || factory == null || challenge == null || challenge.length() > 128) return "";
        try {
            ShotProtocol.Hello hello = factory.open(sender, challenge);
            if (hello.shooter() != sender || !hello.challenge().equals(challenge) || !hello.capable()) return "";
            return ShotProtocol.wire(hello);
        } catch (Exception e) { error = "hello:" + e.getClass().getSimpleName(); return ""; }
    }
    public static boolean serverMessage(int sender, String payload) {
        ShotProtocol peer=server;if (peer==null || !peer.ready(true)) return false;
        try { return peer.receive(sender, ShotProtocol.unwire(payload)); }
        catch (Exception e) { error = "server:" + e.getClass().getSimpleName(); return false; }
    }
    public static boolean clientMessage(String payload) {
        ShotProtocol peer=client;if (peer == null) return false;
        try {
            ShotProtocol.Message message = ShotProtocol.unwire(payload);
            return message instanceof ShotProtocol.Hello hello ? peer.handshake(hello) : peer.confirm(message);
        } catch (Exception e) { error = "client:" + e.getClass().getSimpleName(); return false; }
    }
    public static synchronized String poll() { return outbound.isEmpty() ? "" : outbound.removeFirst(); }
    public static void expire() { ShotProtocol c=client,s=server;if (c != null) c.expire(); if (s != null) s.expire(); }
    public static void disconnect(int shooter) {
        ShotProtocol c=client,s=server;if (c != null) c.disconnect(shooter); if (s != null) s.disconnect(shooter);
        synchronized(ShotProtocolLua.class) { outbound.removeIf(row->row.startsWith(shooter+"|") || (c!=null && row.startsWith("-1|"))); }
    }
    public static void reset() {
        String session = UUID.randomUUID().toString();
        ShotProtocol c=client,s=server;if (c != null) c.reset(session); if (s != null) s.reset(session);
        client = null; server = null; helloFactory = null;
        synchronized(ShotProtocolLua.class) { outbound.clear(); } error = "server-adapter-unavailable";
    }
}
