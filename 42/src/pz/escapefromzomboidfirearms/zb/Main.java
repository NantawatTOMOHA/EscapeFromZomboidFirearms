package pz.escapefromzomboidfirearms.zb;

/**
 * Escape From Zomboid Firearms: ZombieBuddy part (client rendering). Loaded by
 * ZombieBuddy from media/java/client/EscapeFromZomboidFirearms-ZB.jar; the patches of
 * this package are applied automatically, TWZB is the Lua side.
 */
public class Main {
    public static void main(String[] args) {
        System.out.println("[TWZB] Escape From Zomboid Firearms Java part " + TWZB.VERSION + " loaded");
        // Before ZombieBuddy applies this package's patches: every target already loaded (PatchTargets).
        PatchTargets.preload();
    }
}
