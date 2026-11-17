package pz.escapefromzomboidfirearms.zb;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import me.zed_0xff.zombie_buddy.Exposer;
import me.zed_0xff.zombie_buddy.Patch;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.core.skinnedmodel.model.ItemModelRenderer;
import zombie.core.skinnedmodel.model.WorldItemAtlas;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.objects.IsoWorldInventoryObject;

/**
 * Mod weapons arriving on the ground (bug wave 324, 24.10.2026, the user's item 4: "models have no normal look on the
 * ground ... others without half of their parts"). A weapon on the ground is drawn through the HandWeapon branch of
 * ItemModelRenderer: its WeaponSprite render copy and its ModelWeaponPart list, both transient per client (TW/Render).
 * TW_RenderWatch rebuilt them only on LoadGridsquare, so a weapon dropped from a bag, received over the network, dropped
 * by Gunworks or a timed action, or spawned by loot after its square loaded kept an unconfigured copy (the bare
 * receiver, its parts missing or placed for another assembly). Every way an item enters the ground ends in
 * InventoryItem.setWorldItem(object) (IsoGridSquare.AddWorldInventoryItem - both overloads, the string one never calls
 * addToWorld -, IsoWorldInventoryObject.load for saves and the network, swapItem): the exit queues the object of a mod
 * HandWeapon; TW_RenderWatch drains the queue on the main thread (load runs on the chunk streamer: a concurrent queue),
 * refreshes the assembly and calls redraw. redraw drops what the engine cached of the earlier look: the item's
 * WorldItemAtlas texture (ItemTexture.isStillValid ignores the sprite and the parts) and the cached render chunk
 * (invalidateRenderChunkLevel(68), as AddWorldInventoryItem invalidates it).
 */
@Exposer.LuaClass(name = "TWGroundItemsNative")
public final class GroundItems {
    static final String PREFIX = "EscapeFromZomboidFirearms.";
    private static final ConcurrentLinkedQueue<IsoWorldInventoryObject> arrived = new ConcurrentLinkedQueue<>();
    public static volatile long queued, drained, redrawn;

    private GroundItems() { }

    /** A mod firearm (the hidden underbarrel weapon included); grenades, rounds and parts have static ground models. */
    public static boolean ours(InventoryItem item) {
        if (!(item instanceof HandWeapon)) return false;
        String type = item.getFullType();
        return type != null && type.startsWith(PREFIX) && !type.startsWith(PREFIX + "HG_") && !type.startsWith(PREFIX + "X_");
    }

    public static void arrived(InventoryItem item, IsoWorldInventoryObject object) {
        try {
            if (object != null && ours(item)) {
                arrived.add(object);
                queued++;
            }
        } catch (Throwable ignored) { }
    }

    @Patch(className = "zombie.inventory.InventoryItem", methodName = "setWorldItem", strictMatch = true)
    public static final class Arrival {
        @Patch.OnExit
        public static void exit(@Patch.This InventoryItem item, @Patch.Argument(0) IsoWorldInventoryObject object) {
            GroundItems.arrived(item, object);
        }
    }

    /** Main thread (Lua): the objects queued since the last call, still holding their item; null when none. */
    public static ArrayList<IsoWorldInventoryObject> poll() {
        if (arrived.isEmpty()) return null;
        ArrayList<IsoWorldInventoryObject> list = new ArrayList<>();
        IsoWorldInventoryObject object;
        while ((object = arrived.poll()) != null) {
            drained++;
            InventoryItem item = object.getItem();
            if (item != null && item.getWorldItem() == object && !list.contains(object)) list.add(object);
        }
        return list.isEmpty() ? null : list;
    }

    private static Field textureMap, scratch;
    private static Method initParams, keyOf;
    private static boolean evictBroken;
    public static volatile long evicted;

    /**
     * WorldItemAtlas keeps every isometric ground texture for the session in a map keyed by the mesh names of the root
     * and of the parts, angles, scale and tint (getItemKey) - not by the attachments. Vanilla parts have fixed mount
     * points, so equal names meant an equal look; a render copy of ours places the same parts by the assembly (another
     * slot, another rest pose), so a rebuilt look with the same part set found the earlier texture. The entries of the
     * item's current key (both resolutions) are dropped through the atlas's own ItemParams.init and getItemKey; items
     * holding the old ItemTexture keep drawing it until their own look changes. Main thread, as getItemTexture.
     */
    static int evict(InventoryItem item) {
        if (evictBroken) return 0;
        try {
            if (keyOf == null) {
                Class<?> params = Class.forName("zombie.core.skinnedmodel.model.WorldItemAtlas$ItemParams");
                Field map = WorldItemAtlas.class.getDeclaredField("itemTextureMap");
                Field own = WorldItemAtlas.class.getDeclaredField("itemParams");
                Method init = params.getDeclaredMethod("init", InventoryItem.class, boolean.class);
                Method key = WorldItemAtlas.class.getDeclaredMethod("getItemKey", params);
                map.setAccessible(true); own.setAccessible(true); init.setAccessible(true); key.setAccessible(true);
                textureMap = map; scratch = own; initParams = init; keyOf = key;
            }
            WorldItemAtlas atlas = WorldItemAtlas.instance;
            Map<?, ?> map = (Map<?, ?>) textureMap.get(atlas);
            Object params = scratch.get(atlas);
            int n = 0;
            for (boolean high : new boolean[] {false, true}) {
                if (Boolean.TRUE.equals(initParams.invoke(params, item, high)) && map.remove(keyOf.invoke(atlas, params)) != null) n++;
            }
            evicted += n;
            return n;
        } catch (Throwable t) {
            evictBroken = true;
            System.out.println("[TWZB] ground atlas eviction unavailable (the cached texture of an equal part set stays): " + t);
            return 0;
        }
    }

    /** Main thread (Lua): the item's ground look changed - re-render it from the current copy and parts. */
    public static boolean redraw(InventoryItem item) {
        if (item == null) return false;
        try {
            evict(item);
            item.atlasTexture = null;
            IsoWorldInventoryObject object = item.getWorldItem();
            IsoGridSquare square = object == null ? null : object.getRenderSquare();
            if (square != null) square.invalidateRenderChunkLevel(68L);
            redrawn++;
            return square != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean vpChecked, vpReady;
    private static Method vpExisting, vpEntryFor, vpAskAhead;
    private static Field vpEntryLevels, vpModelledItems, vpFadingModelledItems, vpModelsNear, vpLevelData, vpLevelFading;
    private static Field vpAsksBefore, vpAsksNow, vpFrames, vpFrameModelItems, vpFrameScene, vpSceneModels, vpDrawsCount, vpDrawsItems;

    private static boolean ensureVpReflection() {
        if (vpChecked) return vpReady;
        vpChecked = true;
        try {
            Class<?> cc = Class.forName("viewpoint.world.ChunkCache");
            Class<?> entry = Class.forName("viewpoint.world.ChunkCache$Entry");
            Class<?> level = Class.forName("viewpoint.world.ChunkCache$Level");
            Class<?> asks = Class.forName("viewpoint.models.ModelAsks");
            Class<?> models = Class.forName("viewpoint.models.Models");
            Class<?> fp = Class.forName("viewpoint.FP");
            Class<?> frame = Class.forName("viewpoint.core.Frame");
            Class<?> scene = Class.forName("viewpoint.render.SceneData");
            Class<?> draws = Class.forName("viewpoint.render.ModelDraws");
            vpExisting = cc.getDeclaredMethod("existing", IsoChunk.class);
            vpEntryFor = cc.getDeclaredMethod("entryFor", IsoChunk.class);
            vpAskAhead = models.getDeclaredMethod("askAhead", IsoWorldInventoryObject.class, IsoGridSquare.class);
            vpEntryLevels = entry.getDeclaredField("levels");
            vpModelledItems = level.getDeclaredField("modelledItems");
            vpFadingModelledItems = level.getDeclaredField("fadingModelledItems");
            vpModelsNear = level.getDeclaredField("modelsNear");
            vpLevelData = level.getDeclaredField("data");
            vpLevelFading = level.getDeclaredField("fading");
            vpAsksBefore = asks.getDeclaredField("before");
            vpAsksNow = asks.getDeclaredField("now");
            vpFrames = fp.getDeclaredField("frames");
            vpFrameModelItems = frame.getDeclaredField("modelItems");
            vpFrameScene = frame.getDeclaredField("scene");
            vpSceneModels = scene.getDeclaredField("models");
            vpDrawsCount = draws.getDeclaredField("count");
            vpDrawsItems = draws.getDeclaredField("items");
            vpExisting.setAccessible(true); vpEntryFor.setAccessible(true); vpAskAhead.setAccessible(true);
            vpEntryLevels.setAccessible(true); vpModelledItems.setAccessible(true); vpFadingModelledItems.setAccessible(true);
            vpModelsNear.setAccessible(true); vpLevelData.setAccessible(true); vpLevelFading.setAccessible(true);
            vpAsksBefore.setAccessible(true); vpAsksNow.setAccessible(true);
            vpFrames.setAccessible(true); vpFrameModelItems.setAccessible(true); vpFrameScene.setAccessible(true);
            vpSceneModels.setAccessible(true); vpDrawsCount.setAccessible(true); vpDrawsItems.setAccessible(true);
            vpReady = true;
        } catch (Throwable ignored) {
            vpReady = false;
        }
        return vpReady;
    }

    private static void invalidateSquare(IsoGridSquare sq) {
        if (sq == null) return;
        try {
            sq.invalidateRenderChunkLevel(324L);
        } catch (Throwable ignored) { }
    }

    @SuppressWarnings("unchecked")
    private static void removeFromVpChunk(IsoWorldInventoryObject object, IsoGridSquare sq) {
        if (object == null || sq == null || !ensureVpReflection()) return;
        try {
            IsoChunk chunk = sq.getChunk();
            if (chunk == null) return;
            Object entry = vpExisting.invoke(null, chunk);
            if (entry == null) return;
            Object[] levels = (Object[]) vpEntryLevels.get(entry);
            if (levels == null) return;
            for (Object lvl : levels) {
                if (lvl == null) continue;
                ArrayList<IsoWorldInventoryObject> items = (ArrayList<IsoWorldInventoryObject>) vpModelledItems.get(lvl);
                if (items != null) items.remove(object);
                ArrayList<IsoWorldInventoryObject> fading = (ArrayList<IsoWorldInventoryObject>) vpFadingModelledItems.get(lvl);
                if (fading != null) fading.remove(object);
            }
        } catch (Throwable ignored) { }
    }

    @SuppressWarnings("unchecked")
    private static void addToVpChunk(IsoWorldInventoryObject object, IsoGridSquare sq, boolean prewarm) {
        if (object == null || sq == null || !ensureVpReflection()) return;
        try {
            Set<Object> before = (Set<Object>) vpAsksBefore.get(null);
            Set<Object> now = (Set<Object>) vpAsksNow.get(null);
            if (before != null) before.add(object);
            if (now != null) now.add(object);
            if (prewarm) {
                try { vpAskAhead.invoke(null, object, sq); } catch (Throwable ignored) { }
            }
            IsoChunk chunk = sq.getChunk();
            if (chunk == null) return;
            Object entry = vpEntryFor.invoke(null, chunk);
            if (entry == null) return;
            Object[] levels = (Object[]) vpEntryLevels.get(entry);
            if (levels == null) return;
            int idx = sq.getZ() + 32;
            Object target = (idx >= 0 && idx < levels.length) ? levels[idx] : null;
            if (target == null || (vpLevelData.get(target) == null && vpLevelFading.get(target) == null)) {
                for (int i = Math.min(Math.max(idx, 32), levels.length - 1); i >= 0; i--) {
                    Object candidate = levels[i];
                    if (candidate != null && (vpLevelData.get(candidate) != null || vpLevelFading.get(candidate) != null)) {
                        target = candidate;
                        break;
                    }
                }
            }
            if (target != null) {
                ArrayList<IsoWorldInventoryObject> items = (ArrayList<IsoWorldInventoryObject>) vpModelledItems.get(target);
                if (items == null) {
                    items = new ArrayList<>();
                    vpModelledItems.set(target, items);
                }
                if (!items.contains(object)) items.add(object);
                vpModelsNear.setBoolean(target, true);
            }
        } catch (Throwable ignored) { }
    }

    /**
     * W11 grenades (items 12 & 13): keep a thrown grenade or launched ordnance IsoWorldInventoryObject registered in
     * both Isometric (FBORenderCell / FBORenderChunk via invalidateRenderChunkLevel) and Viewpoint (ChunkCache$Level
     * modelledItems + ModelAsks) without calling IsoWorldInventoryObject.setOffset (which sends network SyncIsoObject)
     * and without letting the constructor randomize xoff/yoff == 0.
     */
    public static boolean syncGrenadeVisual(IsoWorldInventoryObject object, IsoGridSquare oldSquare,
            double xoff, double yoff, double zoff, boolean dirtyLook) {
        if (object == null) return false;
        try {
            IsoGridSquare sq = object.getSquare();
            InventoryItem item = object.getItem();
            if (sq == null || item == null) return false;
            object.xoff = (float) xoff;
            object.yoff = (float) yoff;
            object.zoff = (float) zoff;
            if (oldSquare != null && oldSquare != sq) {
                removeFromVpChunk(object, oldSquare);
                invalidateSquare(oldSquare);
            }
            if (dirtyLook) {
                evict(item);
                item.atlasTexture = null;
                invalidateSquare(sq);
                IsoGridSquare renderSq = object.getRenderSquare();
                if (renderSq != null && renderSq != sq) invalidateSquare(renderSq);
            }
            addToVpChunk(object, sq, dirtyLook);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Clean up a grenade visual from both Isometric chunk caches and Viewpoint's modelledItems lists. */
    @SuppressWarnings("unchecked")
    public static boolean removeGrenadeVisual(IsoWorldInventoryObject object, IsoGridSquare oldSquare) {
        if (object == null) return false;
        try {
            IsoGridSquare sq = object.getSquare();
            IsoGridSquare renderSq = object.getRenderSquare();
            if (oldSquare != null) {
                removeFromVpChunk(object, oldSquare);
                invalidateSquare(oldSquare);
            }
            if (sq != null) {
                removeFromVpChunk(object, sq);
                invalidateSquare(sq);
            }
            if (renderSq != null && renderSq != sq) invalidateSquare(renderSq);
            if (ensureVpReflection()) {
                Set<Object> before = (Set<Object>) vpAsksBefore.get(null);
                Set<Object> now = (Set<Object>) vpAsksNow.get(null);
                if (before != null) before.remove(object);
                if (now != null) now.remove(object);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Read-only QA probe of whether a grenade IsoWorldInventoryObject is registered and drawn in Isometric / Viewpoint. */
    @SuppressWarnings("unchecked")
    public static KahluaTable probeGrenadeVisual(IsoWorldInventoryObject object) {
        KahluaTable out = LuaManager.platform.newTable();
        if (object == null) {
            out.rawset("present", false);
            return out;
        }
        try {
            IsoGridSquare sq = object.getSquare();
            InventoryItem item = object.getItem();
            boolean squarePresent = sq != null && sq.getWorldObjects().contains(object) && sq.getObjects().contains(object);
            boolean itemHasModel = item != null && ItemModelRenderer.itemHasModel(item);
            String isoLayer = "None";
            try {
                Object info = object.getRenderInfo(0);
                if (info != null) {
                    Field f = info.getClass().getField("layer");
                    isoLayer = String.valueOf(f.get(info));
                }
            } catch (Throwable ignored) { }
            boolean inVpChunkLevel = false;
            boolean inVpFrameItems = false;
            int vpModelDraws = 0;
            float wx = object.getWorldPosX(), wy = object.getWorldPosY(), wz = object.getWorldPosZ();
            if (ensureVpReflection()) {
                if (sq != null && sq.getChunk() != null) {
                    Object entry = vpExisting.invoke(null, sq.getChunk());
                    if (entry != null) {
                        Object[] levels = (Object[]) vpEntryLevels.get(entry);
                        if (levels != null) {
                            for (Object lvl : levels) {
                                if (lvl == null) continue;
                                if ((vpLevelData.get(lvl) != null || vpLevelFading.get(lvl) != null) && vpModelsNear.getBoolean(lvl)) {
                                    ArrayList<IsoWorldInventoryObject> items = (ArrayList<IsoWorldInventoryObject>) vpModelledItems.get(lvl);
                                    if (items != null && items.contains(object)) {
                                        inVpChunkLevel = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
                Object[] frames = (Object[]) vpFrames.get(null);
                if (frames != null && frames.length > 0 && frames[0] != null) {
                    Object frame0 = frames[0];
                    ArrayList<?> fItems = (ArrayList<?>) vpFrameModelItems.get(frame0);
                    if (fItems != null && fItems.contains(object)) inVpFrameItems = true;
                    Object scene = vpFrameScene.get(frame0);
                    Object draws = scene == null ? null : vpSceneModels.get(scene);
                    if (draws != null) {
                        int count = vpDrawsCount.getInt(draws);
                        ItemModelRenderer[] renderers = (ItemModelRenderer[]) vpDrawsItems.get(draws);
                        if (renderers != null) {
                            for (int i = 0; i < count && i < renderers.length; i++) {
                                ItemModelRenderer r = renderers[i];
                                if (r != null && Math.abs(r.x - wx) < 0.05f && Math.abs(r.y - wy) < 0.05f && Math.abs(r.z - wz) < 0.05f) {
                                    vpModelDraws++;
                                }
                            }
                        }
                    }
                }
            }
            out.rawset("present", true);
            out.rawset("squarePresent", squarePresent);
            out.rawset("itemHasModel", itemHasModel);
            out.rawset("isoLayer", isoLayer);
            out.rawset("inVpChunkLevel", inVpChunkLevel);
            out.rawset("inVpFrameItems", inVpFrameItems);
            out.rawset("vpModelDraws", (double) vpModelDraws);
            out.rawset("worldX", (double) wx);
            out.rawset("worldY", (double) wy);
            out.rawset("worldZ", (double) wz);
        } catch (Throwable t) {
            out.rawset("error", t.toString());
        }
        return out;
    }

    public static String info() {
        return "queued=" + queued + " drained=" + drained + " redrawn=" + redrawn + " evicted=" + evicted + " waiting=" + arrived.size()
                + (evictBroken ? " evict=off" : "");
    }
}
