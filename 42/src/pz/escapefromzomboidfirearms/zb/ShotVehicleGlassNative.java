package pz.escapefromzomboidfirearms.zb;

import java.util.List;
import me.zed_0xff.zombie_buddy.Patch;
import org.joml.Vector3f;
import pz.escapefromzomboidfirearms.zb.ShotContacts.Hit;
import pz.escapefromzomboidfirearms.zb.TarkovBallistics.Vec3;
import zombie.characters.IsoGameCharacter;
import zombie.core.physics.Transform;
import zombie.core.physics.WorldSimulation;
import zombie.inventory.types.HandWeapon;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclePart;

/** The vanilla shot path's pane (W10 carglass, coordinator's option b): client JAR only, inert without the mod's Lua. */
public final class ShotVehicleGlassNative {
    private ShotVehicleGlassNative() {}
    /**
     * Vanilla path (our ballistics do not own the shot): BaseVehicle.processRangeHit damages a random part of the side
     * the shooter stands at. When the shot's line (the muzzle's, or on a server the character's along the look angle)
     * enters the vehicle through intact measured glass, that pane breaks instead; the shot still stops.
     */
    public static boolean nativeRangeHit(BaseVehicle vehicle,IsoGameCharacter attacker,HandWeapon weapon) {
        if (!ModLua.present()) return false;
        if (ShotVehicleGlass.ownedHit() || vehicle==null || attacker==null || weapon==null) return false;
        try {
            VehiclePart part=rayPane(vehicle,attacker,weapon);
            return part!=null && ShotVehicleGlass.breakPane(part);
        } catch (RuntimeException | LinkageError e) {
            System.out.println("[TW-SHOT] vehicle glass: native range hit kept vanilla: "+e);return false;
        }
    }
    static VehiclePart rayPane(BaseVehicle vehicle,IsoGameCharacter attacker,HandWeapon weapon) {
        var sim=WorldSimulation.instance;
        if (sim==null || vehicle.getScript()==null || vehicle.getParts()==null) return null;
        float angle=attacker.getLookAngleRadians();
        // The shot's own line: the muzzle and its 3D direction, as CombatManager.fireWeapon draws the tracer; a server
        // has no drawn muzzle and keeps processRangeHit's line (the character, level along the look angle).
        double sx=attacker.getX(),sy=attacker.getY(),sz=attacker.getZ(),dx=Math.cos(angle),dy=Math.sin(angle),dz=0;
        if (!GameServer.server) {
            var start=new zombie.iso.Vector3();var dir=new zombie.iso.Vector3();
            attacker.getBallisticsController().calculateMuzzlePosition(start,dir);
            double l=Math.sqrt(dir.x*dir.x+dir.y*dir.y+dir.z*dir.z);
            if (Double.isFinite(l) && l>1e-6 && Float.isFinite(start.x) && Float.isFinite(start.y) && Float.isFinite(start.z)) {
                sx=start.x;sy=start.y;sz=start.z;dx=dir.x/l;dy=dir.y/l;dz=dir.z/l;
            }
        }
        Transform transform=vehicle.getWorldTransform(new Transform());
        // Only where the line enters the vehicle matters: it runs past the vehicle's far end (no weapon reach read).
        var extents=vehicle.getScript().getExtents();
        double reach=Math.hypot(transform.origin.x+sim.offsetX-sx,transform.origin.z+sim.offsetY-sy)
                +Math.sqrt(extents.x*extents.x+extents.y*extents.y+extents.z*extents.z)+1;
        Vec3 a=local(transform,sim,sx,sy,sz),b=local(transform,sim,sx+dx*reach,sy+dy*reach,sz+dz*reach);
        // The owner path's own geometry decides (W10 glasspass): the pane is the line's first contact on the vehicle.
        List<Contact> contacts=contacts(vehicle,a,b);
        if (contacts==null || contacts.isEmpty()) return null;
        Contact first=contacts.getFirst();
        return first.glass() && first.part()!=null && ShotVehicleGlass.intact(first.part())?first.part():null;
    }
    /** One contact of a segment with a vehicle's shot geometry: where (fraction), glass or not, its part (null: a shell,
     * the cabin or fixed glass no part owns), and whether it is only the opening of a broken, removed or open pane. */
    record Contact(double fraction,boolean glass,VehiclePart part,boolean opening) { }
    /**
     * A segment (vehicle-local cells) through the owner path's pieces of a vehicle (ShotContactsPz.pieces: measured panes
     * of intact windows, the greenhouse cabin, the split box shells, door, engine and tyre proxies), nearest first; null
     * when the vehicle has no usable geometry. A broken, removed or open pane is no piece; where the segment crosses its
     * measured glass it is an opening contact (the round passes, nothing breaks, nothing is lost).
     */
    static List<Contact> contacts(BaseVehicle vehicle,Vec3 a,Vec3 b) {
        List<Contact> out=new java.util.ArrayList<>();
        boolean ok=ShotContactsPz.pieces(vehicle,(part,suffix,local,material,proof)->{
            Hit h=local.intersect(a,b);
            if (h!=null) out.add(new Contact(h.fraction(),material.startsWith("vehicle-window"),part,false));
        });
        if (!ok) return null;
        ShotVehicleGlass.Measured glass=ShotVehicleGlass.measured(vehicle.getScript());
        if (glass!=null) {
            java.util.Set<String> windows=new java.util.HashSet<>();var parts=vehicle.getParts();
            for (int i=0;i<parts.getPartCount();i++) { var q=parts.getPartByIndex(i);if (q.getWindow()!=null) windows.add(q.getId()); }
            for (ShotVehicleGlass.Pane pane:glass.panes()) {
                String id=ShotVehicleGlass.owner(pane.zone(),windows);
                VehiclePart part=id==null?null:vehicle.getPartById(id);
                if (part==null || ShotVehicleGlass.intact(part)) continue;
                Hit h=pane.shape().intersect(a,b);
                if (h!=null) out.add(new Contact(h.fraction(),true,part,true));
            }
        }
        out.sort((x,y)->Double.compare(x.fraction(),y.fraction()));
        return out;
    }
    /** Iso position (x, y, level z) in a vehicle's local cells, as ShotContactsPz.VehicleShape. */
    static Vec3 local(Transform transform,WorldSimulation sim,double x,double y,double z) {
        Transform inverse=new Transform(transform);inverse.origin.set(0,0,0);inverse.inverse();
        Vector3f d=new Vector3f((float)(x-transform.origin.x-sim.offsetX),
                (float)(z*TarkovBallistics.METRES_PER_LEVEL/TarkovBallistics.METRES_PER_CELL-transform.origin.y),
                (float)(y-transform.origin.z-sim.offsetY));
        inverse.transform(d);return new Vec3(d.x,d.y,d.z);
    }

    @Patch(className="zombie.vehicles.BaseVehicle",methodName="processRangeHit",strictMatch=true)
    public static final class NativeRangeHit {
        @Patch.OnEnter(skipOn=true)
        public static boolean enter(@Patch.This BaseVehicle vehicle,@Patch.Argument(0) IsoGameCharacter attacker,
                @Patch.Argument(1) HandWeapon weapon,@Patch.Local("twGlassPane") boolean pane) {
            if (!ModLua.present()) return false;
            pane=ShotVehicleGlassNative.nativeRangeHit(vehicle,attacker,weapon);return pane;
        }
        @Patch.OnExit
        public static void exit(@Patch.Local("twGlassPane") boolean pane,@Patch.Return(readOnly=false) boolean hit) {
            if (pane) hit=true;
        }
    }
}
