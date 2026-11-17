package pz.escapefromzomboidfirearms.zb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.lwjgl.util.vector.Matrix4f;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoSurvivor;
import zombie.characters.IsoZombie;
import zombie.characters.animals.AnimalDefinitions;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.BodyDamage.BodyPartType;
import zombie.core.physics.RagdollBodyPart;
import zombie.core.physics.Transform;
import zombie.core.physics.WorldSimulation;
import zombie.core.properties.PropertyContainer;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.model.Model;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.LosUtil;
import zombie.iso.Vector3;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.objects.IsoBarricade;
import zombie.iso.objects.IsoCurtain;
import zombie.iso.objects.IsoDeadBody;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoTree;
import zombie.iso.objects.IsoWindow;
import zombie.scripting.objects.VehicleScript;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclePart;
import zombie.vehicles.VehicleParts;
import zombie.vehicles.VehicleWindow;
import pz.escapefromzomboidfirearms.zb.ShotContacts.*;
import pz.escapefromzomboidfirearms.zb.ShotSnapshot.*;
import pz.escapefromzomboidfirearms.zb.ShotPoseSnapshot.Pz;
import pz.escapefromzomboidfirearms.zb.TarkovBallistics.Vec3;

/** PZ world adapter. Every trace reads current object state and bone transforms.
 * Ordinary native cell faces/floors are exact planes, bounded to real vertical
 * extents and window/door apertures rather than full-cell invisible walls.
 * Unmeasured stairs and untyped structures stop conservatively without fabricated damage.
 */
public final class ShotContactsPz implements World {
    // Adapter-specific extension of the existing native guard. Preflight is
    // complete before this adapter can be published or report ready().
    static final String[] THUMPABLE_METHODS={"isDestroyed","isBarricaded","isBlockAllTheSquare","isFloor","isStairs",
            "isCorner","isDoor","isDoorFrame","isWindowN","isWindowW","IsOpen","isCanPassThrough","getNorth"};
    static final String[] FLAG_NAMES={"collideN","collideW","HoppableN","HoppableW","TallHoppableN","TallHoppableW",
            "solid","solidtrans","WindowN","WindowW","DoorWallN","DoorWallW","halfheight","vegitation","canBeCut",
            "water","solidfloor","blocksight","transparentN","transparentW","bed","WallOverlay","FloorOverlay",
            "ontable","attachedFloor","attachedSurface","attachedCeiling","WallN","WallW","WallNW","WallNTrans","WallWTrans"};
    static final String[][] VEHICLE_MEMBERS={
        {"zombie.vehicles.BaseVehicle","getParts","()Lzombie/vehicles/VehicleParts;",""},
        {"zombie.vehicles.VehicleParts","getPartCount","()I",""},
        {"zombie.vehicles.VehicleParts","getPartByIndex","(I)Lzombie/vehicles/VehiclePart;",""},
        {"zombie.vehicles.VehicleParts","contains","(Lzombie/vehicles/VehiclePart;)Z",""},
        {"zombie.vehicles.VehicleParts","getEngine","()Lzombie/vehicles/VehiclePart;",""},
        {"zombie.vehicles.VehiclePart","getId","()Ljava/lang/String;",""},
        {"zombie.vehicles.VehiclePart","getArea","()Ljava/lang/String;",""},
        {"zombie.vehicles.VehiclePart","getVehicle","()Lzombie/vehicles/BaseVehicle;",""},
        {"zombie.vehicles.VehiclePart","getParent","()Lzombie/vehicles/VehiclePart;",""},
        {"zombie.vehicles.VehiclePart","getItemType","()Ljava/util/ArrayList;",""},
        {"zombie.vehicles.VehiclePart","getInventoryItem","()Lzombie/inventory/InventoryItem;",""},
        {"zombie.vehicles.VehiclePart","getCondition","()I",""},
        {"zombie.vehicles.VehiclePart","getWheelIndex","()I",""},
        {"zombie.vehicles.VehiclePart","getWindow","()Lzombie/vehicles/VehicleWindow;",""},
        {"zombie.vehicles.VehiclePart","getDoor","()Lzombie/vehicles/VehicleDoor;",""},
        {"zombie.vehicles.VehicleDoor","isOpen","()Z",""},
        {"zombie.vehicles.VehicleWindow","isDestroyed","()Z",""},
        {"zombie.vehicles.VehicleWindow","isOpen","()Z",""},
        {"zombie.vehicles.VehicleWindow","getOpenDelta","()F",""},
        {"zombie.scripting.objects.VehicleScript","getFullName","()Ljava/lang/String;",""},
        {"zombie.scripting.objects.VehicleScript","getModelOffset","()Lorg/joml/Vector3f;",""},
        {"zombie.scripting.objects.VehicleScript","getModelScale","()F",""},
        {"zombie.scripting.objects.VehicleScript","getAreaById","(Ljava/lang/String;)Lzombie/scripting/objects/VehicleScript$Area;",""},
        {"zombie.scripting.objects.VehicleScript","getWheelCount","()I",""},
        {"zombie.scripting.objects.VehicleScript","getWheel","(I)Lzombie/scripting/objects/VehicleScript$Wheel;",""},
        {"zombie.scripting.objects.VehicleScript","getPhysicsShapeCount","()I",""},
        {"zombie.scripting.objects.VehicleScript","getPhysicsShape","(I)Lzombie/scripting/objects/VehicleScript$PhysicsShape;",""},
        {"zombie.scripting.objects.VehicleScript$PhysicsShape","type","I",""},
        {"zombie.scripting.objects.VehicleScript$PhysicsShape","getOffset","()Lorg/joml/Vector3f;",""},
        {"zombie.scripting.objects.VehicleScript$PhysicsShape","getExtents","()Lorg/joml/Vector3f;",""},
        {"zombie.scripting.objects.VehicleScript$PhysicsShape","getRotate","()Lorg/joml/Vector3f;",""},
        {"zombie.scripting.objects.VehicleScript$Area","x","F",""},
        {"zombie.scripting.objects.VehicleScript$Area","y","F",""},
        {"zombie.scripting.objects.VehicleScript$Area","w","F",""},
        {"zombie.scripting.objects.VehicleScript$Area","h","F",""},
        {"zombie.scripting.objects.VehicleScript$Wheel","getId","()Ljava/lang/String;",""},
        {"zombie.scripting.objects.VehicleScript$Wheel","getOffset","()Lorg/joml/Vector3f;",""},
        {"zombie.scripting.objects.VehicleScript$Wheel","radius","F",""},
        {"zombie.scripting.objects.VehicleScript$Wheel","width","F",""}
    };
    private final Supplier<IsoCell> cell;
    public final ShotTargets targets;
    public final Function<ShotSnapshot,IsoGameCharacter> shooter;
    final ShotNativeGuard guard;
    private final boolean thumpableReady;
    private final Bodies bodies;
    /** Where character colliders come from. Null: the bones this machine drew (SP, the
     * MP shooter's own view). A dedicated server keeps no bone transforms, so it traces
     * the bodies the shooter reported and the server checked (ShotBodies). */
    public interface Bodies {
        void colliders(ShotSnapshot shot,Segment segment,IsoGameCharacter attacker,List<Collider> out) throws Exception;
    }

    public ShotContactsPz(Supplier<IsoCell> cell,ShotTargets targets,
            Function<ShotSnapshot,IsoGameCharacter> shooter,Consumer<String> bridgeFailure) {
        this(cell,targets,shooter,bridgeFailure,null);
    }
    public ShotContactsPz(Supplier<IsoCell> cell,ShotTargets targets,
            Function<ShotSnapshot,IsoGameCharacter> shooter,Consumer<String> bridgeFailure,Bodies bodies) {
        this.cell=java.util.Objects.requireNonNull(cell);this.targets=java.util.Objects.requireNonNull(targets);
        this.bodies=bodies;
        this.shooter=java.util.Objects.requireNonNull(shooter);guard=new ShotNativeGuard(bridgeFailure);
        ClassLoader loader=ShotContactsPz.class.getClassLoader();
        if (guard.verify(loader)) { verifyFlagApi(guard,loader);verifyThumpableApi(guard,loader);verifyVehicleApi(guard,loader); }
        thumpableReady=guard.ready();
    }
    static void verifyFlagApi(ShotNativeGuard guard,ClassLoader loader) {
        try {
            String type="zombie.iso.SpriteDetails.IsoFlagType";
            Class<?> flags=Class.forName(type,false,loader);
            if (!java.lang.reflect.Modifier.isPublic(flags.getModifiers()) || !flags.isEnum()) throw new IllegalAccessException(type);
            Class<?> properties=Class.forName("zombie.core.properties.PropertyContainer",false,loader);
            if (!java.lang.reflect.Modifier.isPublic(properties.getModifiers())) throw new IllegalAccessException(properties.getName());
            properties.getMethod("has",flags);
            guard.verifyMember(loader,new String[]{properties.getName(),"has","(Lzombie/iso/SpriteDetails/IsoFlagType;)Z",""});
            for (String name:FLAG_NAMES) {
                flags.getField(name);
                guard.verifyMember(loader,new String[]{type,name,"Lzombie/iso/SpriteDetails/IsoFlagType;","static"});
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { guard.fail("native flag ABI: "+error); }
    }
    static void verifyThumpableApi(ShotNativeGuard guard,ClassLoader loader) {
        try {
            Class<?> type=Class.forName("zombie.iso.objects.IsoThumpable",false,loader);
            if (!java.lang.reflect.Modifier.isPublic(type.getModifiers())) throw new IllegalAccessException("IsoThumpable visibility");
            for (String name:THUMPABLE_METHODS) {
                // getMethod only accepts public methods. verifyMember then
                // checks the exact declared primitive return/static signature.
                type.getMethod(name);
                guard.verifyMember(loader,new String[]{type.getName(),name,"()Z",""});
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { guard.fail("thumpable ABI: "+error); }
    }
    static void verifyVehicleApi(ShotNativeGuard guard,ClassLoader loader) {
        try {
            for (String[] row:VEHICLE_MEMBERS) {
                Class<?> type=Class.forName(row[0],false,loader);
                if (!java.lang.reflect.Modifier.isPublic(type.getModifiers())) throw new IllegalAccessException(row[0]);
                if (row[2].startsWith("(")) type.getMethod(row[1],java.lang.invoke.MethodType.fromMethodDescriptorString(row[2],loader).parameterArray());
                else type.getField(row[1]);
                guard.verifyMember(loader,row);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { guard.fail("vehicle ABI: "+error); }
    }
    static final String READ_ONLY_SESSION="tp-aim-dot";
    static boolean readOnly(ShotSnapshot shot) {
        return shot!=null && READ_ONLY_SESSION.equals(shot.id().session());
    }
    @Override public boolean ready() { return thumpableReady && guard.ready(); }
    /** Use the same failure sink for ShotDamage, so one native exception reports once. */
    public void fail(String reason) { guard.fail(reason); }
    @Override public List<Collider> colliders(ShotSnapshot shot,Segment segment) throws Exception {
        ShotContacts.validate(shot,segment);
        if (!ready()) throw new IllegalStateException("PZ contact ABI unavailable");
        try {
            IsoCell current=cell.get();
            if (current==null) throw new IllegalStateException("No active PZ cell");
            boolean ro=readOnly(shot);
            if (!ro) beginProbe(shot,segment);
            ArrayList<Collider> out=new ArrayList<>();Pz from=shot.pose().muzzlePz().offset(segment.from());
            Pz to=shot.pose().muzzlePz().offset(segment.to());
            Set<IsoObject> objects=Collections.newSetFromMap(new IdentityHashMap<>());
            ArrayList<IsoGridSquare> crossed=new ArrayList<>();
            for (Cell visit:ShotContacts.cells(from,to)) {
                IsoGridSquare square=current.getGridSquare(visit.x(),visit.y(),visit.z());
                if (square==null) {
                    // A loaded chunk always has its level-0 square; PZ creates a square above the
                    // ground only where a floor or a room exists. A missing square above loaded
                    // ground is therefore air, not missing world: a shot from an upper floor or a
                    // roof leaves the building through it (user's report of 24.10.2026, item 3).
                    if (!legacyTrace && visit.z()!=0 && current.getGridSquare(visit.x(),visit.y(),0)!=null) {
                        if (!ro) {
                            airSquares++;lastAir=visit.x()+":"+visit.y()+":"+visit.z();
                            shotAir++;shotLastAir=lastAir;
                        }
                        continue;
                    }
                    out.add(world("unloaded:"+visit.x()+":"+visit.y()+":"+visit.z(),TargetKind.UNLOADED,
                            tile(shot,visit.x(),visit.y(),visit.z()),"unloaded","PZ.getGridSquare:null"));
                    continue;
                }
                crossed.add(square);
                if (square.isSolidFloor()) out.add(world("floor:"+visit.x()+":"+visit.y()+":"+visit.z(),TargetKind.WORLD,
                        face(shot,square,1),"floor","PZ.isSolidFloor/cell-plane"));
                if (square.HasStairs()) out.add(world("stairs:"+visit.x()+":"+visit.y()+":"+visit.z(),TargetKind.WORLD,
                        tile(shot,visit.x(),visit.y(),visit.z()),"unmeasured-stairs","conservative:PZ.HasStairs/tile-envelope"));
                // PZArrayList.iterator() throws even on an empty list in B42.21.
                // Read through List's indexed API; do not copy/mutate native lists.
                List<IsoObject> regular=square.getObjects(), special=square.getSpecialObjects();
                for (int i=0;i<regular.size();i++) {
                    IsoObject obj=regular.get(i);if (objects.add(obj)) object(out,shot,obj);
                }
                for (int i=0;i<special.size();i++) {
                    IsoObject obj=special.get(i);if (objects.add(obj)) object(out,shot,obj);
                }
                edge(out,shot,current,square,-1,0);edge(out,shot,current,square,0,-1);
            }
            corpses(out,shot,current,crossed,objects);
            for (BaseVehicle vehicle:current.getVehicles()) vehicle(out,shot,segment,vehicle);
            IsoGameCharacter attacker=shooter.apply(shot);
            if (bodies!=null) bodies.colliders(shot,segment,attacker,out);
            else for (var obj:current.getObjectList()) if (obj instanceof IsoGameCharacter character && character!=attacker) {
                int before=out.size();
                character(out,shot,segment,character);
                if (!ro) near(out,shot,before,character,attacker,segment);
            }
            return List.copyOf(out);
        } catch (Exception | LinkageError ex) { guard.fail("contacts: "+ex);throw ex; }
    }
    private void object(List<Collider> out,ShotSnapshot shot,IsoObject obj) {
        IsoGridSquare sq=obj.getSquare();if (sq==null) return;
        if (obj instanceof IsoWindow window) {
            boolean north=window.getNorth();
            Face boundary=face(shot,sq,north?2:0);
            PropertyContainer wp=window.getProperties();
            boolean fullWindow=wp!=null && wp.has("FullWindow");
            if (findEdgeObject(sq,window,north?IsoFlagType.WindowN:IsoFlagType.WindowW)==null) {
                ShotContacts.addWindowFrame(out,targets.identity(window)+"/frame",boundary,fullWindow,windowFramePreset(wp,fullWindow));
            }
            IsoBarricade sameB=window.getBarricadeOnSameSquare(),oppB=window.getBarricadeOnOppositeSquare();
            String bp=strongerBarricade(barricadePreset(sameB),barricadePreset(oppB));
            boolean barricaded=window.isBarricaded() || bp!=null;
            ShotContacts.addWindowAperture(out,targets.identity(window),boundary,fullWindow,
                    window.IsOpen(),window.isSmashed(),window.isGlassRemoved(),window.isDestroyed(),barricaded,bp);
            return;
        }
        if (obj instanceof IsoDoor door) {
            if (door.isDestroyed()) return;
            boolean north=door.getNorth(),open=door.IsOpen();
            PropertyContainer dp=door.getProperties();
            String sound=dp==null?null:dp.get("DoorSound");
            if (open && ("GarageDoor".equals(sound) || "SlidingGlassDoor".equals(sound))) return;
            double top="GarageDoor".equals(sound)?0.92
                    :(dp!=null && (dp.has(IsoFlagType.HoppableN) || dp.has(IsoFlagType.HoppableW)
                        || "WoodGateSmall".equals(sound) || "MetalPoleGateSmall".equals(sound))?0.36:0.86);
            Collider leaf=ShotContacts.doorLeaf(targets.identity(door),face(shot,sq,2),face(shot,sq,0),north,open,top);
            out.add(material(leaf,"door",dp,null));
            addDoorBarricades(out,shot,sq,north,top,targets.identity(door),
                    door.getBarricadeOnSameSquare(),door.getBarricadeOnOppositeSquare());
            return;
        }
        if (obj instanceof IsoBarricade barricade) {
            if (barricade.isDestroyed() || barricade.getBarricadedObject()!=null) return;
            String bp=barricadePreset(barricade);
            out.add(world(targets.identity(barricade),TargetKind.WORLD,offsetFace(shot,sq,0,0.02,0,1,0,0.86),
                    bp==null?"barricade":"barricade|b5="+bp,"PZ.barricade/standalone"));
            return;
        }
        if (obj instanceof IsoCurtain curtain) {
            if (curtain.IsOpen()) return;
            IsoGridSquare opp=curtain.getOppositeSquare();
            int axis=curtain.getNorth()?2:0;
            double offset=0.03;
            if (opp!=null) {
                int dx=opp.getX()-sq.getX(),dy=opp.getY()-sq.getY();
                if (dx==-1 && dy==0) { axis=0;offset=0.03; }
                else if (dx==1 && dy==0) { axis=0;offset=0.97; }
                else if (dx==0 && dy==-1) { axis=2;offset=0.03; }
                else if (dx==0 && dy==1) { axis=2;offset=0.97; }
            }
            PropertyContainer cp=curtain.getProperties();
            String preset=lookupProps("curtain",cp,"closed-sheet");
            out.add(world(targets.identity(curtain),TargetKind.WORLD,offsetFace(shot,sq,axis,offset,0.04,0.96,0.22,0.88),
                    preset==null?"curtain":"curtain|b5="+preset,"PZ.curtain/closed-aperture"));
            return;
        }
        if (obj instanceof IsoThumpable thumpable) { thumpable(out,shot,thumpable);return; }
        PropertyContainer p=obj.getProperties();
        if (p!=null && p.has(IsoFlagType.attachedFloor) && p.has(IsoFlagType.vegitation)) return;
        if (obj instanceof IsoTree || (p!=null && p.has("tree"))) {
            int size=obj instanceof IsoTree tree?tree.getSize():2;
            double half=Math.clamp(0.05+Math.max(0,size)*0.022,0.07,0.16);
            String preset=lookupProps("tree",p,"trunk");
            out.add(world(targets.identity(obj),TargetKind.WORLD,
                    subBox(shot,sq,0.5-half,0.5+half,0.5-half,0.5+half,0,1),
                    preset==null?"tree":"tree|b5="+preset,"PZ.tree/centered-trunk"));
            return;
        }
        if (p==null) return;
        if (p.has(IsoFlagType.water) || p.has(IsoFlagType.solidfloor)
                || p.has(IsoFlagType.FloorOverlay) || p.has(IsoFlagType.attachedCeiling)) return;
        if (isVegetationPassThrough(obj,p)) return;
        boolean edgeN=p.has(IsoFlagType.collideN) || p.has(IsoFlagType.TallHoppableN)
                || p.has(IsoFlagType.DoorWallN) || p.has(IsoFlagType.WindowN);
        boolean edgeW=p.has(IsoFlagType.collideW) || p.has(IsoFlagType.TallHoppableW)
                || p.has(IsoFlagType.DoorWallW) || p.has(IsoFlagType.WindowW);
        if (edgeN) objectFace(out,shot,obj,p,true);
        if (edgeW) objectFace(out,shot,obj,p,false);
        if (edgeN || edgeW) return;
        if (p.has(IsoFlagType.ontable) || p.has(IsoFlagType.attachedSurface)
                || p.has("IsTableTop") || p.has("IsSurfaceOffset")) return;
        boolean surface=positiveProp(p,"Surface");
        if (p.has(IsoFlagType.solid) || p.has(IsoFlagType.solidtrans) || surface) {
            if (p.has("streetlight") || "Tree".equals(p.get("PhysicsShape"))) {
                out.add(material(world(targets.identity(obj),TargetKind.WORLD,
                        subBox(shot,sq,0.41,0.59,0.41,0.59,0,1),"furniture","PZ.pole/centered-column"),"furniture",p,"pole"));
                return;
            }
            double top=volumeTopFraction(p,surface);
            Box box=top>=0.999?tile(shot,sq.getX(),sq.getY(),sq.getZ()):subBox(shot,sq,0,1,0,1,0,top);
            out.add(material(world(targets.identity(obj),TargetKind.WORLD,box,
                    "unmeasured-solid-object","PZ.solid-or-Surface/height-bounded"),"furniture",p,null));
        }
    }
    private void objectFace(List<Collider> out,ShotSnapshot shot,IsoObject object,PropertyContainer p,boolean north) {
        IsoGridSquare sq=object.getSquare();Face boundary=face(shot,sq,north?2:0);
        IsoGridSquare other=cell.get().getGridSquare(sq.getX()-(north?0:1),sq.getY()-(north?1:0),sq.getZ());
        IsoWindow winObj=other==null?null:(sq.getWindowTo(other)!=null?sq.getWindowTo(other):other.getWindowTo(sq));
        IsoObject doorObj=other==null?null:(sq.getDoorTo(other)!=null?sq.getDoorTo(other):other.getDoorTo(sq));
        boolean window=p.has(north?IsoFlagType.WindowN:IsoFlagType.WindowW);
        boolean door=p.has(north?IsoFlagType.DoorWallN:IsoFlagType.DoorWallW);
        String faceTarget=targets.identity(object)+(north?"/N":"/W");
        if ((winObj!=null || doorObj!=null) && !window && !door) {
            // Trim/overlay sprites (e.g. commercial storefront WallOverlay with WallWTrans)
            // share a window/door edge without MaterialType and must not block the opening.
            return;
        }
        if (window) {
            boolean full=p.has("FullWindow") || (winObj!=null && winObj.getProperties()!=null && winObj.getProperties().has("FullWindow"));
            String framePreset=lookupProps("window-frame",p,null);
            ShotContacts.addWindowFrame(out,faceTarget,boundary,full,framePreset);
            return;
        }
        if (door) {
            String framePreset=lookupProps("door-frame",p,null);
            String mat=framePreset==null?"unmeasured-door-frame":"door-frame|b5="+framePreset;
            String proof=framePreset==null?"conservative:door-lintel-untyped":"PZ.door-wall/lintel";
            out.add(world(faceTarget,TargetKind.WORLD,ShotContacts.subFace(boundary,0,1,0.86,1),mat,proof));
            return;
        }
        if (p.has(IsoFlagType.WallOverlay) && lookupProps("wall",p,null)==null) return;
        boolean lowHop=p.has(north?IsoFlagType.HoppableN:IsoFlagType.HoppableW) || p.has(IsoFlagType.HoppableN) || p.has(IsoFlagType.HoppableW);
        boolean tallHop=p.has(north?IsoFlagType.TallHoppableN:IsoFlagType.TallHoppableW);
        String fenceLow=p.get("FenceTypeLow");
        boolean lowFence=lowHop || fenceLow!=null || p.has(IsoFlagType.halfheight) || p.has("IsLow");
        boolean tallFence=!lowFence && (tallHop || p.get("FenceTypeHigh")!=null);
        double top="RoadBlock".equals(fenceLow)?0.24:lowFence?0.36:tallFence?0.85:1.0;
        Face shaped=top>=0.999?boundary:ShotContacts.subFace(boundary,0,1,0,top);
        String category=(lowFence || tallFence)?"fence":"wall";
        boolean transparent=p.has(north?IsoFlagType.transparentN:IsoFlagType.transparentW)
                || p.has(north?IsoFlagType.WallNTrans:IsoFlagType.WallWTrans);
        String state=category.equals("fence") && (transparent || !p.has(IsoFlagType.blocksight))?"open-railing":null;
        out.add(material(world(targets.identity(object),TargetKind.WORLD,shaped,"wall",
                north?"PZ.edgeN/bounded-face":"PZ.edgeW/bounded-face"),category,p,state));
    }
    private static Collider material(Collider collider,String category,PropertyContainer props,String state) {
        String preset=lookupProps(category,props,state);
        return preset==null?collider:new Collider(collider.target(),collider.kind(),collider.shape(),collider.ragdollPart(),
                collider.bodyPart(),collider.material()+"|b5="+preset,collider.proof());
    }
    private static String lookupProps(String category,PropertyContainer props,String state) {
        if (props==null && state==null) return null;
        return ShotWorldMaterials.lookup(category,key->{
            if ("state".equals(key)) return state;
            return props==null?null:props.get(key);
        });
    }
    private static String windowFramePreset(PropertyContainer wp,boolean fullWindow) {
        if (wp==null) return fullWindow?"MetalThin":null;
        String direct=lookupProps("wall",wp,null);
        if (direct!=null && !"Glass".equals(direct)) return direct;
        String group=wp.get("GroupName");
        if ("Metal".equals(group) || "Chrome".equals(group)) return "MetalThin";
        if ("Wooden".equals(group) || "White Window".equals(group) || "Wood".equals(group) || "Tiled".equals(group)) return "WoodThin";
        return fullWindow?"MetalThin":direct;
    }
    private static String barricadePreset(IsoBarricade b) {
        if (b==null || b.isDestroyed()) return null;
        if (b.isMetal()) return ShotWorldMaterials.lookup("barricade",k->"state".equals(k)?"metal-sheet":null);
        if (b.isMetalBar()) return ShotWorldMaterials.lookup("barricade",k->"state".equals(k)?"metal-bars":null);
        if (b.getNumPlanks()>0) return ShotWorldMaterials.lookup("barricade",k->"state".equals(k)?"wood-plank":null);
        return null;
    }
    private static String strongerBarricade(String a,String b) {
        if (a==null) return b;
        if (b==null) return a;
        if ("MetalThin".equals(a) || "MetalThin".equals(b)) return "MetalThin";
        if ("WoodThin".equals(a) || "WoodThin".equals(b)) return "WoodThin";
        return a;
    }
    private void addDoorBarricades(List<Collider> out,ShotSnapshot shot,IsoGridSquare sq,boolean north,double top,
            String baseTarget,IsoBarricade same,IsoBarricade opp) {
        int axis=north?2:0;
        String sp=barricadePreset(same);
        if (sp!=null) out.add(world(baseTarget+"/barricade-same",TargetKind.WORLD,
                offsetFace(shot,sq,axis,0.02,0,1,0,top),"barricade|b5="+sp,"PZ.barricade/same-square"));
        String op=barricadePreset(opp);
        if (op!=null) {
            IsoGridSquare osq=opp.getSquare();
            if (osq!=null) out.add(world(baseTarget+"/barricade-opp",TargetKind.WORLD,
                    offsetFace(shot,osq,axis,0.98,0,1,0,top),"barricade|b5="+op,"PZ.barricade/opposite-square"));
        }
    }
    private static IsoObject findEdgeObject(IsoGridSquare sq,IsoObject exclude,IsoFlagType flag) {
        List<IsoObject> objs=sq.getObjects();
        for (int i=0;i<objs.size();i++) {
            IsoObject o=objs.get(i);
            if (o!=exclude && o.getProperties()!=null && o.getProperties().has(flag)) return o;
        }
        return null;
    }
    private static boolean isVegetationPassThrough(IsoObject obj,PropertyContainer p) {
        if (obj.isBush() || p.has(IsoFlagType.vegitation) || p.has("Bush") || p.has(IsoFlagType.canBeCut)) return true;
        return "Vegitation".equals(p.get("MoveType")) && !"Stone".equals(p.get("Material"));
    }
    private static boolean positiveProp(PropertyContainer p,String key) {
        if (!p.has(key)) return false;
        try { return Double.parseDouble(p.get(key))>0; }
        catch (NumberFormatException | NullPointerException ex) { return true; }
    }
    private static double parseProp(PropertyContainer p,String key) {
        if (!p.has(key)) return -1;
        try { return Double.parseDouble(p.get(key)); }
        catch (NumberFormatException | NullPointerException ex) { return -1; }
    }
    private static double volumeTopFraction(PropertyContainer p,boolean hasSurface) {
        double px=Math.max(parseProp(p,"ItemHeight"),parseProp(p,"Surface"));
        if (px>0) return Math.clamp(px/96.0,0.08,1.0);
        if (p.has(IsoFlagType.halfheight) || p.has("IsLow")) return 0.36;
        if (p.has(IsoFlagType.bed) || p.has("SeatMaterial")) return 0.34;
        if (p.has(IsoFlagType.solidtrans)) return p.has(IsoFlagType.blocksight)?0.85:0.42;
        return hasSurface && !p.has(IsoFlagType.solid)?0.36:1.0;
    }
    private void thumpable(List<Collider> out,ShotSnapshot shot,IsoThumpable object) {
        if (object.isDestroyed()) return;
        IsoGridSquare sq=object.getSquare();PropertyContainer p=object.getProperties();
        if (p==null || object.isStairs()) {
            out.add(world(targets.identity(object),TargetKind.WORLD,tile(shot,sq.getX(),sq.getY(),sq.getZ()),
                    "unmeasured-thumpable","conservative:thumpable-null-props-or-stairs"));
            return;
        }
        if (object.isFloor()) {
            out.add(material(world(targets.identity(object),TargetKind.WORLD,face(shot,sq,1),
                    "floor","PZ.thumpable-floor/cell-plane"),"floor",p,null));
            return;
        }
        IsoBarricade sameB=object.getBarricadeOnSameSquare(),oppB=object.getBarricadeOnOppositeSquare();
        if (object.isBarricaded() && barricadePreset(sameB)==null && barricadePreset(oppB)==null) {
            out.add(world(targets.identity(object),TargetKind.WORLD,tile(shot,sq.getX(),sq.getY(),sq.getZ()),
                    "unmeasured-thumpable","conservative:thumpable-barricade-uninspectable"));
            return;
        }
        if (object.isBlockAllTheSquare() || p.has(IsoFlagType.solid) || p.has(IsoFlagType.solidtrans) || positiveProp(p,"Surface")) {
            double top=object.isBlockAllTheSquare() && parseProp(p,"ItemHeight")<=0 && parseProp(p,"Surface")<=0
                    ?1.0:volumeTopFraction(p,positiveProp(p,"Surface"));
            Box box=top>=0.999?tile(shot,sq.getX(),sq.getY(),sq.getZ()):subBox(shot,sq,0,1,0,1,0,top);
            String cat=object.isBlockAllTheSquare()?"unknown":"furniture";
            out.add(material(world(targets.identity(object),TargetKind.WORLD,box,
                    "unmeasured-thumpable","PZ.thumpable-volume/height-bounded"),cat,p,null));
            return;
        }
        if (object.isCorner()) {
            out.add(material(world(targets.identity(object)+"/N",TargetKind.WORLD,face(shot,sq,2),
                    "thumpable","PZ.thumpable-corner/north-face"),"thumpable",p,null));
            out.add(material(world(targets.identity(object)+"/W",TargetKind.WORLD,face(shot,sq,0),
                    "thumpable","PZ.thumpable-corner/west-face"),"thumpable",p,null));
            return;
        }
        if (object.isDoor()) {
            boolean north=object.getNorth();
            double top=(p.has(IsoFlagType.HoppableN) || p.has(IsoFlagType.HoppableW))?0.36:0.86;
            Collider leaf=ShotContacts.doorLeaf(targets.identity(object),face(shot,sq,2),face(shot,sq,0),north,object.IsOpen(),top);
            out.add(material(leaf,"thumpable",p,null));
            addDoorBarricades(out,shot,sq,north,top,targets.identity(object),sameB,oppB);
            return;
        }
        if (object.isDoorFrame() || object.isWindowN() || object.isWindowW()) {
            if (lookupProps("thumpable",p,null)!=null) {
                if (p.has(IsoFlagType.collideN) || p.has(IsoFlagType.collideW) || object.isCanPassThrough()) {
                    if (p.has(IsoFlagType.collideN)) out.add(material(world(targets.identity(object),TargetKind.WORLD,face(shot,sq,2),
                            "thumpable","PZ.thumpable-frame/collideN"),"thumpable",p,null));
                    if (p.has(IsoFlagType.collideW)) out.add(material(world(targets.identity(object),TargetKind.WORLD,face(shot,sq,0),
                            "thumpable","PZ.thumpable-frame/collideW"),"thumpable",p,null));
                    return;
                }
            }
            out.add(world(targets.identity(object),TargetKind.WORLD,tile(shot,sq.getX(),sq.getY(),sq.getZ()),
                    "unmeasured-thumpable","conservative:thumpable-frame-unmeasured"));
            return;
        }
        boolean north=object.getNorth();
        boolean hop=p.has(IsoFlagType.HoppableN) || p.has(IsoFlagType.HoppableW) || p.get("FenceTypeLow")!=null;
        boolean tall=p.has(IsoFlagType.TallHoppableN) || p.has(IsoFlagType.TallHoppableW) || p.get("FenceTypeHigh")!=null;
        if (hop || tall) {
            double top=hop?0.36:0.85;
            Face f=ShotContacts.subFace(face(shot,sq,north?2:0),0,1,0,top);
            out.add(material(world(targets.identity(object),TargetKind.WORLD,f,
                    "thumpable","PZ.thumpable-fence/bounded-face"),"thumpable",p,null));
            addDoorBarricades(out,shot,sq,north,top,targets.identity(object),sameB,oppB);
            return;
        }
        if (object.IsOpen() || object.isCanPassThrough()) return;
        out.add(material(world(targets.identity(object),TargetKind.WORLD,face(shot,sq,north?2:0),
                "thumpable","PZ.thumpable-TestCollide/"+(north?"north-face":"west-face")),"thumpable",p,null));
        addDoorBarricades(out,shot,sq,north,1.0,targets.identity(object),sameB,oppB);
    }
    /** Reconcile both directions of native adjacency. LOS alone is never a 3D ray. */
    private void edge(List<Collider> out,ShotSnapshot shot,IsoCell cell,IsoGridSquare sq,int dx,int dy) {
        IsoGridSquare other=cell.getGridSquare(sq.getX()+dx,sq.getY()+dy,sq.getZ());if (other==null) return;
        if (sq.getWindowTo(other)!=null || other.getWindowTo(sq)!=null
                || sq.getDoorTo(other)!=null || other.getDoorTo(sq)!=null) return;
        boolean north=dx==0;
        Face boundary=face(shot,sq,north?2:0);
        for (Collider represented:out) {
            if (represented.shape() instanceof Face f && f.axis()==boundary.axis()
                    && Math.abs(f.coordinate()-boundary.coordinate())<1e-4f && tangentialOverlap(f,boundary)) return;
        }
        if (hasHandledEdgeObject(sq,north,true) || hasHandledEdgeObject(other,north,false)) return;
        LosUtil.TestResults a=sq.testVisionAdjacent(dx,dy,0,false,false);
        LosUtil.TestResults b=other.testVisionAdjacent(-dx,-dy,0,false,false);
        if (a==LosUtil.TestResults.Blocked || b==LosUtil.TestResults.Blocked) {
            if ((hasNonWallVisionBlocker(sq) || hasNonWallVisionBlocker(other)) && !hasUnrepresentedWall(sq,north)) return;
            out.add(world("opaque-edge:"+sq.getX()+":"+sq.getY()+":"+sq.getZ()+":"+dx+":"+dy,
                    TargetKind.WORLD,boundary,"unmeasured-opaque-edge","conservative:PZ.testVisionAdjacent:Blocked"));
        }
    }
    private static boolean tangentialOverlap(Face a,Face b) {
        if (a.axis()==0) return a.max().z()>b.min().z()+1e-5f && b.max().z()>a.min().z()+1e-5f;
        return a.max().x()>b.min().x()+1e-5f && b.max().x()>a.min().x()+1e-5f;
    }
    private static boolean hasHandledEdgeObject(IsoGridSquare sq,boolean north,boolean sameSquare) {
        List<IsoObject> special=sq.getSpecialObjects();
        for (int i=0;i<special.size();i++) {
            IsoObject o=special.get(i);
            if (o instanceof IsoCurtain || o instanceof IsoBarricade || o instanceof IsoThumpable) return true;
        }
        if (!sameSquare) return false;
        List<IsoObject> objs=sq.getObjects();
        for (int i=0;i<objs.size();i++) {
            IsoObject o=objs.get(i);
            if (o instanceof IsoCurtain || o instanceof IsoBarricade || o instanceof IsoThumpable) return true;
            PropertyContainer p=o.getProperties();if (p==null) continue;
            if (p.has(north?IsoFlagType.WindowN:IsoFlagType.WindowW)
                    || p.has(north?IsoFlagType.DoorWallN:IsoFlagType.DoorWallW)
                    || p.has(north?IsoFlagType.HoppableN:IsoFlagType.HoppableW)
                    || p.has(north?IsoFlagType.TallHoppableN:IsoFlagType.TallHoppableW)) return true;
        }
        return false;
    }
    private static boolean hasNonWallVisionBlocker(IsoGridSquare sq) {
        List<IsoObject> objs=sq.getObjects();
        for (int i=0;i<objs.size();i++) {
            IsoObject o=objs.get(i);
            PropertyContainer p=o.getProperties();
            if (o instanceof IsoTree || (p!=null && (p.has("tree")
                    || ((p.has(IsoFlagType.solid) || p.has(IsoFlagType.solidtrans)) && p.has(IsoFlagType.blocksight))))) return true;
        }
        return false;
    }
    private static boolean hasUnrepresentedWall(IsoGridSquare sq,boolean north) {
        String rawFlag=north?"collideN":"collideW";
        List<IsoObject> objs=sq.getObjects();
        for (int i=0;i<objs.size();i++) {
            PropertyContainer p=objs.get(i).getProperties();
            if (p!=null && p.has(rawFlag) && !p.has(north?IsoFlagType.collideN:IsoFlagType.collideW)) return true;
        }
        return false;
    }
    private void character(List<Collider> out,ShotSnapshot shot,Segment segment,IsoGameCharacter target) {
        // A body this side cannot place in the shot's frame is skipped, never fatal. A custom body
        // (an animal, a corpse) or a remote character the server has not positioned yet reports
        // NaN/Inf: the old envelope built NaN capsules from it and the whole contact collection
        // threw, so the isometric MP round was spent with no claim (REPORT-integiso 8.1).
        if (!placed(target)) { if (!readOnly(shot)) unplaced(target,"contacts");return; }
        // This bound comes from the entire original body height (under 1 model
        // unit) transformed by native 1.5 tiles/unit, rounded outward to 2 tiles.
        // Only the supported unscaled native skeletons use this broad phase.
        Pz a=shot.pose().muzzlePz().offset(segment.from()), b=shot.pose().muzzlePz().offset(segment.to());
        if (target.getX()<Math.min(a.x(),b.x())-2 || target.getX()>Math.max(a.x(),b.x())+2
                || target.getY()<Math.min(a.y(),b.y())-2 || target.getY()>Math.max(a.y(),b.y())+2) return;
        // W11 corpses: a dead body is a contact of the round (Tarkov's dead-body layer), not a target to wound.
        if (target.isDead()) { corpse(out,shot,target);return; }
        // An animal (W10 srvlive): its own drawn bones, or its own coarse body - never a person's 1.75 m column.
        if (target instanceof IsoAnimal animal) { animal(out,shot,animal);return; }
        AnimationPlayer animation=target.getAnimationPlayer();
        boolean nativeBody=target.getClass()==IsoPlayer.class || target.getClass()==IsoZombie.class || target.getClass()==IsoSurvivor.class;
        // User's report of 24.10.2026, item 3: a zombie whose skeleton is not posed on this side got a
        // 4x4-cell, two-level box of kind WORLD - the bullet was eaten by a wall around it and the
        // zombie took no damage, and that box also stopped shots aimed at anything near it. Such a
        // body now gets its own coarse envelope of CHARACTER kind (envelope()): a bullet that
        // reaches the body hurts it, one aimed elsewhere passes it.
        if (!nativeBody || animation==null || !animation.isReady()) {
            if (legacyTrace) { legacyBox(out,shot,target); return; }
            envelope(out,shot,target,nativeBody?"skeleton-unready":"custom-body");return;
        }
        var zones=target.isFemale()?ShotZonesData.F:ShotZonesData.M;
        var capsules=zones(shot,target,animation);
        if (capsules==null) {
            if (legacyTrace) { legacyBox(out,shot,target); return; }
            envelope(out,shot,target,"pose-not-finite");return;
        }
        for (int i=0;i<zones.size();i++) {
            ShotZonesData.Zone zone=zones.get(i);ShotBodies.Zone c=capsules.get(i);
            out.add(new Collider(targets.identity(target),TargetKind.CHARACTER,new Capsule(c.a(),c.b(),c.radius()),c.ragdoll(),c.body(),
                    "native-body","measured-PZ-"+(target.isFemale()?"F/":"M/")
                    +(target instanceof IsoZombie?"source-body=zombie/":"source-body=human/")+zone.bone()+"/"+zone.bodyPart()));
        }
    }
    /**
     * W11 corpses: a dead body is a contact of the round, never a target to wound. Tarkov's bullet hit mask includes
     * the dead-body layer (EFT.Ballistics HitMask; Corpse re-layers the whole body and ApplyDeath keeps its colliders
     * enabled), and a corpse collider is the same BodyPartCollider a living body has: a round with enough penetration
     * power passes the flesh, one without stops (client.cs BodyPartCollider.IsPenetrated / EBulletState.StopHit).
     * ShotPenetration resolves the flesh preset from the proof's zone; ShotDamagePz gives the contact native blood
     * only. The drawn death pose (the death-fall/ragdoll bones) is used while this side has one, else the coarse body
     * the mod already gives an unready character (lying once the body is down).
     */
    private void corpse(List<Collider> out,ShotSnapshot shot,IsoGameCharacter target) {
        if (target instanceof IsoAnimal animal) { corpseAnimal(out,shot,animal);return; }
        List<ShotBodies.Zone> capsules=zones(shot,target);
        if (capsules!=null) {
            var zones=target.isFemale()?ShotZonesData.F:ShotZonesData.M;
            for (int i=0;i<zones.size();i++) {
                ShotZonesData.Zone zone=zones.get(i);ShotBodies.Zone c=capsules.get(i);
                out.add(new Collider(targets.identity(target),TargetKind.CHARACTER,new Capsule(c.a(),c.b(),c.radius()),
                        c.ragdoll(),c.body(),"corpse-body","corpse/native-body/"+(target.isFemale()?"F/":"M/")
                        +(target instanceof IsoZombie?"source-body=zombie/":"source-body=human/")+zone.bone()+"/"+zone.bodyPart()));
            }
            if (!readOnly(shot)) { corpseBones++;lastCorpseBody=corpseName(target)+" bones";corpseBodies++; }
            return;
        }
        List<EnvelopeZone> envelope=envelope(shot,target);
        if (envelope.isEmpty()) return;
        for (EnvelopeZone zone:envelope)
            out.add(new Collider(targets.identity(target),TargetKind.CHARACTER,new Capsule(zone.a(),zone.b(),zone.radius()),
                    zone.ragdoll(),zone.body(),"corpse-body","corpse/envelope/"+zone.bodyPart()));
        if (!readOnly(shot)) { corpseEnvelopes++;lastCorpseBody=corpseName(target)+" envelope";corpseBodies++; }
    }
    /** A dead animal: its own drawn bones when this side has them, else the lying trunk sized by PZ's own collision
     * width of the species - never a person's 1.75 m body. A species with no size is skipped and counted (W11). */
    private void corpseAnimal(List<Collider> out,ShotSnapshot shot,IsoAnimal target) {
        List<ShotBodies.Zone> capsules=animalZones(shot,target);
        if (capsules!=null) {
            for (ShotBodies.Zone c:capsules) out.add(new Collider(targets.identity(target),TargetKind.CHARACTER,
                    new Capsule(c.a(),c.b(),c.radius()),c.ragdoll(),c.body(),"corpse-body",
                    "corpse/animal-bones/"+target.getAnimalType()));
            if (!readOnly(shot)) { corpseBones++;lastCorpseBody=corpseName(target)+" bones";corpseBodies++; }
            return;
        }
        float radius=animalRadius(target,0), cell=(float)TarkovBallistics.METRES_PER_CELL, level=(float)TarkovBallistics.METRES_PER_LEVEL;
        var def=target.getAdef();
        if (def==null || !(def.collisionSize>0) || !(target.getAnimalSize()>0)) {
            if (!readOnly(shot)) { corpseSkipped++;lastCorpseBody=corpseName(target)+" no-size"; }
            return;
        }
        float dx=1,dy=0;
        var facing=target.getForwardDirection();
        double length=facing==null?0:Math.hypot(facing.x,facing.y);
        if (Double.isFinite(length) && length>1e-4) { dx=(float)(facing.x/length);dy=(float)(facing.y/length); }
        double x=target.getX(),y=target.getY(),z=target.getZ()+0.5*radius/level, half=3*radius/cell;
        out.add(animalCorpseZone(target,shot,"Bip01_Spine",target.isFemale(),x-dx*half,y-dy*half,z,x,y,z,radius));
        out.add(animalCorpseZone(target,shot,"Bip01_Head",target.isFemale(),x+dx*half,y+dy*half,z,
                x+dx*half*1.3,y+dy*half*1.3,z,Math.max(0.04f,0.55f*radius)));
        if (!readOnly(shot)) { corpseEnvelopes++;lastCorpseBody=corpseName(target)+" animal-envelope";corpseBodies++; }
    }
    /** A settled corpse from the squares on the round's path. PZ keeps it in the square's static moving objects,
     * not in the cell's character list, so only the crossed squares and their eight neighbours are read (a lying body
     * reaches at most one square from its centre) - never a scan of the cell's corpses. */
    private void corpses(List<Collider> out,ShotSnapshot shot,IsoCell cell,List<IsoGridSquare> crossed,Set<IsoObject> seen) {
        for (IsoGridSquare square:crossed) for (int dx=-1;dx<=1;dx++) for (int dy=-1;dy<=1;dy++) {
            IsoGridSquare near=cell.getGridSquare(square.getX()+dx,square.getY()+dy,square.getZ());
            if (near==null) continue;
            var moving=near.getStaticMovingObjects();
            for (int i=0;i<moving.size();i++) {
                IsoMovingObject object=moving.get(i);
                if (object instanceof IsoDeadBody body && seen.add(body)) corpse(out,shot,body);
            }
        }
    }
    /**
     * A settled corpse (IsoDeadBody). PZ draws it from its corpse atlas and keeps no 3D body, so the lying footprint
     * is the one the heat pass already draws for a lying human: 1.75 m along the body's angle by 0.44 m, 0.32 m
     * thick, split into the same zones a living body uses so the Tarkov flesh preset is the part the round hit.
     * A hooked carcass hangs above its square; an animal corpse uses the species' own collision width.
     */
    private void corpse(List<Collider> out,ShotSnapshot shot,IsoDeadBody body) {
        if (!placed(body.getX(),body.getY(),body.getZ()) || body.getSquare()==null) {
            if (!readOnly(shot)) { unplacedBodies++;lastUnplacedBody="IsoDeadBody contacts at="+body.getX()+","+body.getY()+","+body.getZ(); }
            return;
        }
        boolean animal=body.isAnimal() || body.isAnimalSkeleton(), female=body.isFemale();
        float radius=animal?animalCorpseRadius(body):0.22f;
        if (!(radius>0)) {
            if (!readOnly(shot)) { corpseSkipped++;lastCorpseBody="IsoDeadBody animal="+body.getAnimalType()+" no-size"; }
            return;
        }
        double angle=body.getAngle(), cell=TarkovBallistics.METRES_PER_CELL, level=TarkovBallistics.METRES_PER_LEVEL;
        double x=body.getX(),y=body.getY(),z=body.getZ();
        if (body.isOnHook()) {
            out.add(corpseZone(body,shot,"Bip01_Spine",female,x,y,z+0.18/level,x,y,z+0.95/level,radius,"corpse/dead-body/hanging/"));
            out.add(corpseZone(body,shot,"Bip01_Head",female,x,y,z+0.95/level,x,y,z+1.20/level,
                    Math.max(0.08f,0.55f*radius),"corpse/dead-body/hanging/"));
        } else if (animal) {
            double ux=Math.cos(angle),uy=Math.sin(angle),half=3*radius/cell,h=z+0.5*radius/level;
            out.add(corpseZone(body,shot,"Bip01_Spine",female,x-ux*half,y-uy*half,h,x+ux*half,y+uy*half,h,radius,
                    "corpse/dead-body/animal/"+body.getAnimalType()+"/"));
            out.add(corpseZone(body,shot,"Bip01_Head",female,x+ux*half,y+uy*half,h,x+ux*half*1.3,y+uy*half*1.3,h,
                    Math.max(0.04f,0.55f*radius),"corpse/dead-body/animal/"+body.getAnimalType()+"/"));
        } else {
            // The head lies along the body's angle when the body fell on its face, behind it when it fell on its back.
            double sign=body.isFallOnFront()?1:-1, ux=Math.cos(angle)*sign, uy=Math.sin(angle)*sign;
            out.add(corpseZone(body,shot,"Bip01_L_Thigh",female,x+ux*-0.85/cell,y+uy*-0.85/cell,z+0.10/level,
                    x+ux*-0.30/cell,y+uy*-0.30/cell,z+0.10/level,0.11f,"corpse/dead-body/"));
            out.add(corpseZone(body,shot,"Bip01_Spine",female,x+ux*-0.30/cell,y+uy*-0.30/cell,z+0.16/level,
                    x+ux*0.10/cell,y+uy*0.10/cell,z+0.16/level,0.16f,"corpse/dead-body/"));
            out.add(corpseZone(body,shot,"Bip01_Spine1",female,x+ux*0.10/cell,y+uy*0.10/cell,z+0.16/level,
                    x+ux*0.60/cell,y+uy*0.60/cell,z+0.16/level,0.17f,"corpse/dead-body/"));
            out.add(corpseZone(body,shot,"Bip01_Head",female,x+ux*0.62/cell,y+uy*0.62/cell,z+0.15/level,
                    x+ux*0.82/cell,y+uy*0.82/cell,z+0.15/level,0.10f,"corpse/dead-body/"));
        }
        if (!readOnly(shot)) { corpseBodies++;corpseEnvelopes++;lastCorpseBody=corpseName(body)+(animal?" animal="+body.getAnimalType():"")+" dead-body"; }
    }
    private Collider corpseZone(IsoObject body,ShotSnapshot shot,String bone,boolean female,
            double ax,double ay,double az,double bx,double by,double bz,float radius,String proof) {
        ShotZonesData.Zone zone=null;
        for (ShotZonesData.Zone candidate:(female?ShotZonesData.F:ShotZonesData.M))
            if (candidate.bone().equals(bone)) { zone=candidate;break; }
        if (zone==null) throw new IllegalStateException("Missing native body bone "+bone);
        return new Collider(targets.identity(body),TargetKind.CHARACTER,
                new Capsule(metric(shot,ax,ay,az),metric(shot,bx,by,bz),radius),
                RagdollBodyPart.valueOf(zone.ragdoll()).ordinal(),BodyPartType.ToIndex(BodyPartType.valueOf(zone.bodyPart())),
                "corpse-body",proof+zone.bodyPart());
    }
    private Collider animalCorpseZone(IsoObject body,ShotSnapshot shot,String bone,boolean female,
            double ax,double ay,double az,double bx,double by,double bz,float radius) {
        return corpseZone(body,shot,bone,female,ax,ay,az,bx,by,bz,radius,"corpse/dead-body/animal/");
    }
    /** The species' own body width from PZ's collision size (the live animal path), or -1 when the type has none. */
    private static float animalCorpseRadius(IsoDeadBody body) {
        AnimalDefinitions def=AnimalDefinitions.getDef(body.getAnimalType());
        float size=body.getAnimalSize();
        if (def==null || !(def.collisionSize>0) || !(size>0)) return -1;
        double radius=0.5*def.collisionSize*size*TarkovBallistics.METRES_PER_CELL;
        return Double.isFinite(radius)?(float)Math.max(0.04,Math.min(0.45,radius)):-1;
    }
    private static String corpseName(Object body) {
        if (body instanceof IsoDeadBody dead) return "IsoDeadBody"+(dead.isZombie()?" zombie":"")+(dead.isSkeleton()?" skeleton":"");
        if (body instanceof IsoGameCharacter character) return character.getClass().getSimpleName()+"#"+character.getOnlineID();
        return body.getClass().getSimpleName();
    }
    /** Characters whose drawn pose was not finite (counted, never a fault), and the last one. */
    static volatile long unreadyPoses;
    static volatile String lastUnreadyPose="none";
    /** Missing squares above loaded ground that the trace passes as air (item 3), and the last one. */
    static volatile long airSquares;
    static volatile String lastAir="none";
    /** Characters traced by the coarse envelope instead of their drawn skeleton, and the last one. */
    static volatile long fallbackBodies;
    static volatile String lastFallbackBody="none";
    /** W11 corpses: corpse colliders built per shot (drawn death bones, coarse envelope, settled dead body). */
    static volatile long corpseBodies,corpseBones,corpseEnvelopes,corpseSkipped;
    static volatile String lastCorpseBody="none";
    /**
     * Bodies and vehicles this side could not place: a custom body (animal, corpse), a remote
     * character the server has not positioned yet, or a vehicle whose transform is not finite.
     * They are skipped, never fatal, and never reported as an MP fallback body (MP isometric,
     * 26.10.2026). Counted so a QA run tells a skipped body apart from a body that was not there.
     */
    static volatile long unplacedBodies;
    static volatile String lastUnplacedBody="none";
    /** True when a PZ position can be placed in the shot's frame. */
    static boolean placed(double x,double y,double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }
    public static boolean placed(IsoGameCharacter target) {
        return target!=null && placed(target.getX(),target.getY(),target.getZ());
    }
    private static void unplaced(IsoGameCharacter target,String where) {
        unplacedBodies++;
        lastUnplacedBody=target.getClass().getSimpleName()+"#"+target.getOnlineID()+" "+where
                +" at="+target.getX()+","+target.getY()+","+target.getZ();
    }
    private static void unplacedVehicle(BaseVehicle vehicle,String where) {
        unplacedBodies++;
        // No vehicle member beyond what every adapter stub has: the class and the object's identity
        // name it well enough for a QA log.
        lastUnplacedBody=vehicle.getClass().getSimpleName()+"@"+Integer.toHexString(System.identityHashCode(vehicle))+" "+where;
    }
    /** Missing squares this shot's trace passed as air (the cumulative counter above is a lifetime one). */
    static volatile long shotAir;
    static volatile String shotLastAir="none";
    /**
     * W6 item 3, second pass: how close the shot's own line came to each body's hit volume. The health of
     * one target cannot tell a plain miss (the line passed outside the drawn body) from a hit that
     * registered no damage (the line went through the body, so the trace must wound it). This records
     * both distances per character: the launch line before any contact (ideal) and the traced segments
     * (live), each minus the capsule radius - a negative clearance means the line crossed the body.
     * The aim error (yaw and the height at the target's range) is kept with it, so a miss is explained
     * by a number instead of by an assumption. Diagnostic only: no collider, flight or damage uses it.
     */
    static final class Near {
        final String target;
        double ideal=Double.POSITIVE_INFINITY,idealAt,idealRadius;
        String idealPart="none";
        double live=Double.POSITIVE_INFINITY,liveAt,liveRadius;
        String livePart="none";
        double yawDeg=Double.NaN,verticalCm=Double.NaN;
        double x=Double.NaN,y=Double.NaN,z=Double.NaN;
        /** The launch line and the aim error are measured once, on the first segment that draws this body. */
        boolean launchDone;
        Near(String target) { this.target=target; }
    }
    private static String probeShot="none";
    private static final java.util.LinkedHashMap<String,Near> probe=new java.util.LinkedHashMap<>();
    public static final int PROBE_LIMIT=4;
    private static Vec3 probeLaunch;
    /** Start the per-shot measurement; true on the shot's first traced segment (the fire-time bodies). */
    private static boolean beginProbe(ShotSnapshot shot,Segment segment) {
        String key=shot.id().session()+"/"+shot.id().sequenceText();
        if (key.equals(probeShot)) return false;
        probeShot=key;probe.clear();shotAir=0;shotLastAir="none";
        // The launch line is the cartridge's own first pellet direction from the drawn muzzle, not a
        // traced segment's chord: the flight's first sample is already a step inside the trajectory.
        probeLaunch=shot.pelletDirections().isEmpty()?null:shot.pelletDirections().get(0).unit();
        return true;
    }
    /** Measure the colliders just built for one character against the launch line and this segment. */
    private void near(List<Collider> out,ShotSnapshot shot,int from,IsoGameCharacter target,IsoGameCharacter attacker,Segment segment) {
        if (out.size()<=from) return;
        Near record=probe.computeIfAbsent(out.get(from).target(),Near::new);
        record.x=target.getX();record.y=target.getY();record.z=target.getZ();
        // The launch line is measured on the first segment that actually draws this body: the point
        // blank scout (eye to muzzle) of prepare() traces the same shot but no capsule of a distant
        // target, so it must not consume the one measurement.
        boolean launch=!record.launchDone && probeLaunch!=null;
        for (int i=from;i<out.size();i++) {
            Collider collider=out.get(i);
            if (!(collider.shape() instanceof Capsule capsule)) continue;
            if (launch) {
                double[] ideal=raySegment(probeLaunch,capsule.a(),capsule.b());
                if (ideal[0]-capsule.radius()<record.ideal) {
                    record.ideal=ideal[0]-capsule.radius();record.idealAt=ideal[1];record.idealRadius=capsule.radius();
                    record.idealPart=part(collider.proof());
                }
            }
            double[] live=segmentSegment(segment.from(),segment.to(),capsule.a(),capsule.b());
            if (live[0]-capsule.radius()<record.live) {
                record.live=live[0]-capsule.radius();record.liveAt=segment.from().length()+live[1];
                record.liveRadius=capsule.radius();record.livePart=part(collider.proof());
            }
        }
        if (launch) {
            record.launchDone=true;
            if (attacker!=null && attacker!=target) {
                record.yawDeg=yawError(target,attacker);
                record.verticalCm=verticalError(shot,target,attacker);
            }
        }
    }
    private static String part(String proof) {
        if (proof==null) return "none";
        String name=proof.substring(proof.lastIndexOf('/')+1);
        return name.replaceAll("[\\s:|,]+","_");
    }
    /** Bearing of the launch from the shooter's position to the target's position, degrees (negative: left). */
    static double yawError(IsoGameCharacter target,IsoGameCharacter shooter) {
        if (probeLaunch==null) return Double.NaN;
        double dx=target.getX()-shooter.getX(),dy=target.getY()-shooter.getY();
        if (Math.abs(dx)<1e-6 && Math.abs(dy)<1e-6) return Double.NaN;
        double bearing=Math.toDegrees(Math.atan2(probeLaunch.z(),probeLaunch.x()));
        double wanted=Math.toDegrees(Math.atan2(dy,dx));
        double difference=bearing-wanted;
        while (difference>180) difference-=360;
        while (difference<-180) difference+=360;
        return difference;
    }
    /** The launch line's height at the target's range against its chest (1.3 m), metres (negative: low). */
    double verticalError(ShotSnapshot shot,IsoGameCharacter target,IsoGameCharacter shooter) {
        if (probeLaunch==null || shot==null) return Double.NaN;
        double flat=Math.hypot(probeLaunch.x(),probeLaunch.z());
        if (flat<1e-4) return Double.NaN;
        double range=Math.hypot(target.getX()-shooter.getX(),target.getY()-shooter.getY())*TarkovBallistics.METRES_PER_CELL;
        double atRange=range/flat*probeLaunch.y();
        double chest=(target.getZ()-shot.pose().muzzlePz().z())*TarkovBallistics.METRES_PER_LEVEL+1.3;
        return atRange-chest;
    }
    /** Distance from a ray (origin, unit direction) to the segment a-b, and the ray distance of the closest point. */
    static double[] raySegment(Vec3 direction,Vec3 a,Vec3 b) {
        Vec3 e=b.minus(a),f=a;
        double B=ShotContacts.dot(direction,e),C=ShotContacts.dot(e,e),D=ShotContacts.dot(direction,f),E=ShotContacts.dot(e,f);
        double t,u;
        if (C<=1e-9) { u=0;t=Math.max(0,D); }
        else {
            double denom=C-B*B;
            t=denom>1e-9?Math.max(0,(C*D-B*E)/denom):Math.max(0,D);
            u=(B*t-E)/C;
            if (u<0) { u=0;t=Math.max(0,D); }
            else if (u>1) { u=1;t=Math.max(0,D+B); }
        }
        Vec3 first=direction.times((float)t),second=a.plus(e.times((float)u));
        return new double[]{first.minus(second).length(),t};
    }
    /** Closest distance between two segments and the parameter on the first (0..1). */
    static double[] segmentSegment(Vec3 p0,Vec3 p1,Vec3 a,Vec3 b) {
        Vec3 d1=p1.minus(p0),d2=b.minus(a),r=p0.minus(a);
        double A=ShotContacts.dot(d1,d1),E=ShotContacts.dot(d2,d2),F=ShotContacts.dot(d2,r);
        double s,t;
        if (A<=1e-9 && E<=1e-9) { s=0;t=0; }
        else if (A<=1e-9) { s=0;t=Math.clamp(F/E,0,1); }
        else {
            double C=ShotContacts.dot(d1,r);
            if (E<=1e-9) { t=0;s=Math.clamp(-C/A,0,1); }
            else {
                double B=ShotContacts.dot(d1,d2),denom=A*E-B*B;
                s=denom>1e-9?Math.clamp((B*F-C*E)/denom,0,1):0;
                t=(B*s+F)/E;
                if (t<0) { t=0;s=Math.clamp(-C/A,0,1); }
                else if (t>1) { t=1;s=Math.clamp((B-C)/A,0,1); }
            }
        }
        Vec3 first=p0.plus(d1.times((float)s)),second=a.plus(d2.times((float)t));
        return new double[]{first.minus(second).length(),s};
    }
    /** The per-shot measurements, nearest body first (bounded, one token, no spaces). */
    public static String probeInfo() {
        if (probe.isEmpty()) return "probe=none";
        ArrayList<Near> rows=new ArrayList<>(probe.values());
        rows.sort((a,b)->Double.compare(a.ideal,b.ideal));
        StringBuilder text=new StringBuilder("probe=");
        for (int i=0;i<Math.min(PROBE_LIMIT,rows.size());i++) {
            Near row=rows.get(i);
            if (i>0) text.append(',');
            // target|idealClearanceM|idealAtM|idealPart|liveClearanceM|liveAtM|livePart|yawDeg|verticalCm|x|y|z
            text.append(row.target).append('|').append(cell(row.ideal)).append('|').append(cell(row.idealAt))
                    .append('|').append(row.idealPart).append('|').append(cell(row.live)).append('|').append(cell(row.liveAt))
                    .append('|').append(row.livePart).append('|').append(degrees(row.yawDeg))
                    .append('|').append(centimetres(row.verticalCm))
                    .append('|').append(cell(row.x)).append('|').append(cell(row.y)).append('|').append(cell(row.z));
        }
        return text.append(" airThisShot=").append(shotAir).append(" lastAirThisShot={").append(shotLastAir).append('}').toString();
    }
    private static String cell(double metres) {
        return Double.isFinite(metres)?String.format(java.util.Locale.ROOT,"%.3f",metres):"nan";
    }
    private static String degrees(double value) {
        return Double.isFinite(value)?String.format(java.util.Locale.ROOT,"%.3f",value):"nan";
    }
    private static String centimetres(double value) {
        return Double.isFinite(value)?String.format(java.util.Locale.ROOT,"%.1f",value*100):"nan";
    }
    /** QA (W6 item 3, A/B in one run): the trace as it was before 24.10.2026 - a missing square above
     * loaded ground stops the bullet, and a character whose skeleton cannot be measured is a 4x4-cell,
     * two-level box of kind WORLD that blocks the bullet and cannot be wounded. */
    static volatile boolean legacyTrace;
    public static void qaLegacy(boolean on) { legacyTrace=on; }
    public static String poseInfo() {
        return "unreadyPoses="+unreadyPoses+" lastUnreadyPose={"+lastUnreadyPose+"}"
                +" airSquares="+airSquares+" lastAir={"+lastAir+"}"
                +" fallbackBodies="+fallbackBodies+" lastFallbackBody={"+lastFallbackBody+"}"
                +" unplacedBodies="+unplacedBodies+" lastUnplacedBody={"+lastUnplacedBody+"}"
                +" corpseBodies="+corpseBodies+" corpseBones="+corpseBones+" corpseEnvelopes="+corpseEnvelopes
                +" corpseSkipped="+corpseSkipped+" lastCorpse={"+lastCorpseBody+"}"
                +" "+probeInfo();
    }
    /** One capsule of the coarse body, with the native zone it stands for. */
    public record EnvelopeZone(int ragdoll,int body,String bodyPart,Vec3 a,Vec3 b,float radius) { }
    /**
     * The body of a character whose drawn skeleton this side cannot measure, as a coarse standing
     * (or lying) humanoid in metres relative to the shot's muzzle: legs, lower and upper torso, neck,
     * head - never a box around the character. Sizes are the ordinary adult body (1.75 m tall,
     * 0.42 m shoulders), so it is not larger than the drawn zombie; a bullet that reaches the body
     * hurts it and one aimed past it passes. The same zones are what an MP client reports for a
     * body it has not animated, so the server can hit it too (ShotOwner.bodies).
     */
    public static List<EnvelopeZone> envelope(ShotSnapshot shot,IsoGameCharacter target) {
        // No placement, no capsules: a body at NaN would make every zone NaN and the metric
        // constructor would throw (MP isometric, 26.10.2026). Skipped and counted.
        if (!placed(target)) { if (!readOnly(shot)) unplaced(target,"envelope");return List.of(); }
        boolean female=target.isFemale();
        var zones=female?ShotZonesData.F:ShotZonesData.M;
        ArrayList<EnvelopeZone> out=new ArrayList<>(5);
        boolean sitting=(target instanceof IsoZombie sz && sz.isSitAgainstWall())
                || target.isSitOnGround() || target.isSittingOnFurniture();
        // W11 corpses: a dead body that ragdolls is down even before PZ's knocked/prone flags say so.
        boolean lying=!sitting && (target.isKnockedDown() || target instanceof IsoZombie zombie && (zombie.isProne() || zombie.isCrawling())
                || target.isDead() && (target.isRagdoll() || target.isRagdollFall() || target.isRagdollSimulationActive()));
        double x=target.getX(),y=target.getY(),z=target.getZ();
        if (sitting) {
            add(out,shot,zones,female,"Bip01_L_Thigh",x,y,z+0.05/TarkovBallistics.METRES_PER_LEVEL,x,y,z+0.42/TarkovBallistics.METRES_PER_LEVEL,0.20f);
            add(out,shot,zones,female,"Bip01_Spine",x,y,z+0.42/TarkovBallistics.METRES_PER_LEVEL,x,y,z+0.68/TarkovBallistics.METRES_PER_LEVEL,0.20f);
            add(out,shot,zones,female,"Bip01_Spine1",x,y,z+0.68/TarkovBallistics.METRES_PER_LEVEL,x,y,z+0.90/TarkovBallistics.METRES_PER_LEVEL,0.21f);
            add(out,shot,zones,female,"Bip01_Neck",x,y,z+0.90/TarkovBallistics.METRES_PER_LEVEL,x,y,z+0.98/TarkovBallistics.METRES_PER_LEVEL,0.09f);
            add(out,shot,zones,female,"Bip01_Head",x,y,z+0.98/TarkovBallistics.METRES_PER_LEVEL,x,y,z+1.16/TarkovBallistics.METRES_PER_LEVEL,0.11f);
            return List.copyOf(out);
        }
        if (lying) {
            // Along the facing direction, on the floor: torso and head.
            double dx=0,dy=0;
            var facing=target.getForwardDirection();
            double length=facing==null?0:Math.hypot(facing.x,facing.y);
            if (Double.isFinite(length) && length>1e-4) { dx=facing.x/length;dy=facing.y/length; }
            add(out,shot,zones,female,"Bip01_Spine",x+dx*0.10,y+dy*0.10,z+0.18/TarkovBallistics.METRES_PER_LEVEL,x+dx*1.05,y+dy*1.05,z+0.18/TarkovBallistics.METRES_PER_LEVEL,0.20f);
            add(out,shot,zones,female,"Bip01_Head",x+dx*1.05,y+dy*1.05,z+0.20/TarkovBallistics.METRES_PER_LEVEL,x+dx*1.30,y+dy*1.30,z+0.20/TarkovBallistics.METRES_PER_LEVEL,0.11f);
            return List.copyOf(out);
        }
        add(out,shot,zones,female,"Bip01_L_Thigh",x,y,z+0.05/TarkovBallistics.METRES_PER_LEVEL,x,y,z+0.85/TarkovBallistics.METRES_PER_LEVEL,0.16f);
        add(out,shot,zones,female,"Bip01_Spine",x,y,z+0.85/TarkovBallistics.METRES_PER_LEVEL,x,y,z+1.18/TarkovBallistics.METRES_PER_LEVEL,0.20f);
        add(out,shot,zones,female,"Bip01_Spine1",x,y,z+1.18/TarkovBallistics.METRES_PER_LEVEL,x,y,z+1.45/TarkovBallistics.METRES_PER_LEVEL,0.21f);
        add(out,shot,zones,female,"Bip01_Neck",x,y,z+1.45/TarkovBallistics.METRES_PER_LEVEL,x,y,z+1.56/TarkovBallistics.METRES_PER_LEVEL,0.09f);
        add(out,shot,zones,female,"Bip01_Head",x,y,z+1.56/TarkovBallistics.METRES_PER_LEVEL,x,y,z+1.75/TarkovBallistics.METRES_PER_LEVEL,0.11f);
        return List.copyOf(out);
    }
    private static void add(List<EnvelopeZone> out,ShotSnapshot shot,List<ShotZonesData.Zone> zones,boolean female,
            String bone,double ax,double ay,double az,double bx,double by,double bz,float radius) {
        ShotZonesData.Zone zone=null;
        for (ShotZonesData.Zone candidate:zones) if (candidate.bone().equals(bone)) { zone=candidate;break; }
        if (zone==null) throw new IllegalStateException("Missing native body bone "+bone);
        out.add(new EnvelopeZone(RagdollBodyPart.valueOf(zone.ragdoll()).ordinal(),
                BodyPartType.ToIndex(BodyPartType.valueOf(zone.bodyPart())),zone.bodyPart(),
                metric(shot,ax,ay,az),metric(shot,bx,by,bz),radius));
    }
    private void envelope(List<Collider> out,ShotSnapshot shot,IsoGameCharacter target,String reason) {
        List<EnvelopeZone> zones=envelope(shot,target);
        // The body could not be placed (or carried no finite zone): it is not a fallback body.
        if (zones.isEmpty()) return;
        if (!readOnly(shot)) {
            fallbackBodies++;lastFallbackBody=target.getClass().getSimpleName()+"#"+target.getOnlineID()+" "+reason
                    +" at="+target.getX()+","+target.getY()+","+target.getZ();
        }
        for (EnvelopeZone zone:zones) {
            out.add(new Collider(targets.identity(target),TargetKind.CHARACTER,new Capsule(zone.a(),zone.b(),zone.radius()),
                    zone.ragdoll(),zone.body(),"native-body","fallback-envelope:"+reason+"/"+zone.bodyPart()));
        }
    }
    private void animal(List<Collider> out,ShotSnapshot shot,IsoAnimal target) {
        List<ShotBodies.Zone> zones=animalZones(shot,target);
        String proof="animal-bones";
        if (zones==null) {
            zones=animalEnvelope(shot,target);proof="animal-envelope";
            if (zones.isEmpty()) return;
            if (!readOnly(shot)) {
                fallbackBodies++;lastFallbackBody="IsoAnimal#"+target.getOnlineID()+" "+target.getAnimalType()+" animal-envelope"
                        +" at="+target.getX()+","+target.getY()+","+target.getZ();
            }
        }
        for (ShotBodies.Zone c:zones) out.add(new Collider(targets.identity(target),TargetKind.CHARACTER,new Capsule(c.a(),c.b(),c.radius()),
                c.ragdoll(),c.body(),"native-body",proof+"/"+target.getAnimalType()));
    }
    /** A bone chain segment of an animal: the zone (ragdoll part and body part) of the person's bone of the same name. */
    private record AnimalSegment(String zoneBone,String from,String to) { }
    private static final List<AnimalSegment> ANIMAL_LIMBS=List.of(
            new AnimalSegment("Bip01_L_Thigh","Bip01_L_Thigh","Bip01_L_Calf"),new AnimalSegment("Bip01_L_Calf","Bip01_L_Calf","Bip01_L_Foot"),
            new AnimalSegment("Bip01_R_Thigh","Bip01_R_Thigh","Bip01_R_Calf"),new AnimalSegment("Bip01_R_Calf","Bip01_R_Calf","Bip01_R_Foot"),
            new AnimalSegment("Bip01_L_UpperArm","Bip01_L_UpperArm","Bip01_L_Forearm"),new AnimalSegment("Bip01_L_Forearm","Bip01_L_Forearm","Bip01_L_Hand"),
            new AnimalSegment("Bip01_R_UpperArm","Bip01_R_UpperArm","Bip01_R_Forearm"),new AnimalSegment("Bip01_R_Forearm","Bip01_R_Forearm","Bip01_R_Hand"));
    /** Animals, W10 srvlive: the body radius from PZ's own collision width of the animal (AnimalDefinitions.collisionSize
     * cells times its model size, IsoAnimal width), else from its drawn trunk (chicks have no collision size). */
    static float animalRadius(IsoAnimal target,float trunkMetres) {
        var adef=target.getAdef();
        float size=target.getAnimalSize();
        double radius=adef!=null && adef.collisionSize>0 && size>0 ? 0.5*adef.collisionSize*size*TarkovBallistics.METRES_PER_CELL
                : 0.3*trunkMetres;
        if (!Double.isFinite(radius)) radius=0.06;
        return (float)Math.max(0.04,Math.min(0.45,radius));
    }
    /**
     * The drawn body of an animal as metric capsules relative to the shot's muzzle, or null when this side has not
     * posed its skeleton (W10 srvlive: Live shots never hit animals). Every B42 species is rigged with the Biped bone
     * names (pelvis, spine, neck, head, thighs, calves, feet; front legs or wings as arms); a bone's origin is where
     * PZ's own Model.boneToWorldCoords puts it, its model transform scaled by IsoAnimal.getAnimalSize. Capsules run along
     * the chains pelvis-spine(-spine1)-neck-head-snout and along each leg; each carries the zone of the person's bone of
     * the same name (a head shot is a head shot for PZ's damage), with radii from the animal's collision width.
     */
    public static List<ShotBodies.Zone> animalZones(ShotSnapshot shot,IsoAnimal target) {
        if (!placed(target)) return null;
        AnimationPlayer animation=target.getAnimationPlayer();
        float size=target.getAnimalSize();
        if (animation==null || !animation.isReady() || !(size>0) || !Float.isFinite(size)) return null;
        Vec3 pelvis=animalBone(shot,target,animation,size,"Bip01_Pelvis"),spine=animalBone(shot,target,animation,size,"Bip01_Spine");
        Vec3 neck=animalBone(shot,target,animation,size,"Bip01_Neck"),head=animalBone(shot,target,animation,size,"Bip01_Head");
        if (pelvis==null || spine==null || neck==null || head==null) {
            if (!readOnly(shot)) {
                unreadyPoses++;
                lastUnreadyPose="IsoAnimal#"+target.getOnlineID()+" "+target.getAnimalType()+" at="+target.getX()+","+target.getY()+","+target.getZ();
            }
            return null;
        }
        Vec3 spine1=animalBone(shot,target,animation,size,"Bip01_Spine1"),snout=animalBone(shot,target,animation,size,"Bip01_HeadNub");
        if (snout==null || snout.minus(head).length()<1e-3f) snout=head.plus(head.minus(neck).times(0.6f));
        float r=animalRadius(target,pelvis.minus(neck).length());
        // The rump reaches behind the pelvis bone by about half the body's thickness.
        Vec3 back=pelvis.minus(spine);
        Vec3 rump=back.length()>1e-4f?pelvis.plus(back.unit().times(0.5f*r)):pelvis;
        ArrayList<ShotBodies.Zone> out=new ArrayList<>();
        // The spine bones run along the top of the back: the trunk is a second tier of capsules 1.2 radii below them
        // (kept above the animal's floor), so the belly is body too (measured in game: a cow was hit only when aimed at
        // its back, 1.0-1.2 m; rounds at 0.4-0.8 m passed under it, sp-scan-1).
        float floor=metric(shot,target.getX(),target.getY(),target.getZ()).y();
        animalZone(out,"Bip01_Pelvis",rump,spine,r);
        animalZone(out,"Bip01_Pelvis",belly(rump,r,floor),belly(spine,r,floor),r);
        if (spine1!=null) {
            animalZone(out,"Bip01_Spine",spine,spine1,r);animalZone(out,"Bip01_Spine1",spine1,neck,r);
            animalZone(out,"Bip01_Spine",belly(spine,r,floor),belly(spine1,r,floor),r);
        } else {
            animalZone(out,"Bip01_Spine1",spine,neck,r);
            animalZone(out,"Bip01_Spine",belly(spine,r,floor),belly(neck,r,floor),r);
        }
        animalZone(out,"Bip01_Neck",neck,head,Math.max(0.03f,0.5f*r));
        animalZone(out,"Bip01_Head",head,snout,Math.max(0.03f,0.55f*r));
        float limb=Math.max(0.02f,0.25f*r);
        for (AnimalSegment segment:ANIMAL_LIMBS) {
            Vec3 a=animalBone(shot,target,animation,size,segment.from()),b=a==null?null:animalBone(shot,target,animation,size,segment.to());
            if (b!=null) animalZone(out,segment.zoneBone(),a,b,limb);
        }
        return out;
    }
    /** A point of the spine line lowered to the belly tier: 1.2 radii down, its capsule never below the floor. */
    private static Vec3 belly(Vec3 spine,float r,float floor) {
        return new Vec3(spine.x(),Math.max(floor+0.9f*r,spine.y()-1.2f*r),spine.z());
    }
    private static Vec3 animalBone(ShotSnapshot shot,IsoAnimal target,AnimationPlayer animation,float size,String bone) {
        int index=animation.getSkinningBoneIndex(bone,-1);
        if (index<0) return null;
        Matrix4f m=drawnBone(animation,index);
        if (!finite(m)) return null;
        Vector3 p=new Vector3(m.m03*size,m.m13*size,m.m23*size);
        Model.vectorToWorldCoords(0,0,0,animation.getRenderedAngle(),p);
        if (!Float.isFinite(p.x) || !Float.isFinite(p.y) || !Float.isFinite(p.z)) return null;
        return metric(shot,(double)target.getX()+p.x,(double)target.getY()+p.y,(double)target.getZ()+p.z);
    }
    private static void animalZone(List<ShotBodies.Zone> out,String bone,Vec3 a,Vec3 b,float radius) {
        ShotZonesData.Zone zone=null;
        for (ShotZonesData.Zone candidate:ShotZonesData.M) if (candidate.bone().equals(bone)) { zone=candidate;break; }
        if (zone==null) throw new IllegalStateException("Missing native body bone "+bone);
        out.add(new ShotBodies.Zone(RagdollBodyPart.valueOf(zone.ragdoll()).ordinal(),BodyPartType.ToIndex(BodyPartType.valueOf(zone.bodyPart())),
                a,b,radius));
    }
    /** The coarse body of an animal whose skeleton this side has not posed: a trunk along its facing at the height of a
     * standing animal, legs under it and a head in front, sized from its collision width (animalRadius). */
    public static List<ShotBodies.Zone> animalEnvelope(ShotSnapshot shot,IsoAnimal target) {
        if (!placed(target) || target.isDead()) { if (!placed(target) && !readOnly(shot)) unplaced(target,"animal-envelope");return List.of(); }
        float r=animalRadius(target,0);
        double dx=1,dy=0;
        var facing=target.getForwardDirection();
        double length=facing==null?0:Math.hypot(facing.x,facing.y);
        if (Double.isFinite(length) && length>1e-4) { dx=facing.x/length;dy=facing.y/length; }
        double x=target.getX(),y=target.getY(),z=target.getZ();
        double half=3*r/TarkovBallistics.METRES_PER_CELL,trunk=3*r/TarkovBallistics.METRES_PER_LEVEL;
        double back=3.6*r/TarkovBallistics.METRES_PER_LEVEL,belly=2.4*r/TarkovBallistics.METRES_PER_LEVEL;
        double headTop=(3*r+0.6*r)/TarkovBallistics.METRES_PER_LEVEL,snout=(3*r+1.2*r)/TarkovBallistics.METRES_PER_CELL;
        ArrayList<ShotBodies.Zone> out=new ArrayList<>(4);
        // Two trunk tiers (back and belly) as the drawn-bone body has.
        animalZone(out,"Bip01_Spine1",metric(shot,x-dx*half,y-dy*half,z+back),metric(shot,x+dx*half,y+dy*half,z+back),r);
        animalZone(out,"Bip01_Spine",metric(shot,x-dx*half,y-dy*half,z+belly),metric(shot,x+dx*half,y+dy*half,z+belly),r);
        animalZone(out,"Bip01_L_Thigh",metric(shot,x,y,z+0.5*r/TarkovBallistics.METRES_PER_LEVEL),metric(shot,x,y,z+trunk),Math.max(0.03f,0.6f*r));
        animalZone(out,"Bip01_Head",metric(shot,x+dx*half,y+dy*half,z+headTop),metric(shot,x+dx*snout,y+dy*snout,z+headTop),
                Math.max(0.03f,0.55f*r));
        return out;
    }
    /** QA A/B only: the fabricated 4x4-cell, two-level box of the trace before 24.10.2026. */
    private void legacyBox(List<Collider> out,ShotSnapshot shot,IsoGameCharacter target) {
        Vec3 min=metric(shot,target.getX()-2,target.getY()-2,target.getZ()-1);
        Vec3 max=metric(shot,target.getX()+2,target.getY()+2,target.getZ()+1);
        out.add(world(targets.identity(target),TargetKind.WORLD,new Box(min,max),"unavailable-skeleton",
                "conservative:custom-or-unready-character"));
    }
    /** The drawn body of a supported native character as metric capsules relative to the
     * shot's muzzle, or null (custom/unready skeleton). The MP shooter reports these. */
    public static List<ShotBodies.Zone> zones(ShotSnapshot shot,IsoGameCharacter target) {
        if (!placed(target)) return null;
        AnimationPlayer animation=target.getAnimationPlayer();
        boolean nativeBody=target.getClass()==IsoPlayer.class || target.getClass()==IsoZombie.class || target.getClass()==IsoSurvivor.class;
        if (!nativeBody || animation==null || !animation.isReady()) return null;
        return zones(shot,target,animation);
    }
    /** Null when the drawn pose is not finite: a skeleton PZ has not posed yet on this side (in MP a
     * remote character this client has not animated, mplive-04), the same as an unready one. */
    private static List<ShotBodies.Zone> zones(ShotSnapshot shot,IsoGameCharacter target,AnimationPlayer animation) {
        ArrayList<ShotBodies.Zone> result=new ArrayList<>();
        for (ShotZonesData.Zone zone:target.isFemale()?ShotZonesData.F:ShotZonesData.M) {
            int index=animation.getSkinningBoneIndex(zone.bone(),-1);
            if (index<0) throw new IllegalStateException("Missing native body bone "+zone.bone());
            Matrix4f matrix=drawnBone(animation,index);
            if (!finite(matrix)) matrix=animation.getBoneModelTransform(index,new Matrix4f());
            Vec3 start=finite(matrix)?bonePoint(shot,target,matrix,zone.a()):null;
            Vec3 end=start==null?null:bonePoint(shot,target,matrix,zone.b());
            if (end==null) {
                if (!readOnly(shot)) {
                    unreadyPoses++;
                    lastUnreadyPose=target.getClass().getSimpleName()+"#"+target.getOnlineID()+" bone="+zone.bone()
                            +" angle="+animation.getRenderedAngle()+" at="+target.getX()+","+target.getY()+","+target.getZ();
                }
                return null;
            }
            double scale=Math.max(Math.sqrt(matrix.m00*matrix.m00+matrix.m10*matrix.m10+matrix.m20*matrix.m20),
                    Math.max(Math.sqrt(matrix.m01*matrix.m01+matrix.m11*matrix.m11+matrix.m21*matrix.m21),
                             Math.sqrt(matrix.m02*matrix.m02+matrix.m12*matrix.m12+matrix.m22*matrix.m22)));
            float radius=(float)(zone.radius()*scale*1.5*TarkovBallistics.METRES_PER_CELL);
            int rag=RagdollBodyPart.valueOf(zone.ragdoll()).ordinal();
            int body=BodyPartType.ToIndex(BodyPartType.valueOf(zone.bodyPart()));
            result.add(new ShotBodies.Zone(rag,body,start,end,radius));
        }
        return result;
    }
    /**
     * A bone's model transform as drawn: the animation player's model transforms, of which the skinning
     * matrices are made. getBoneModelTransform rebuilds it from the local bone transforms, which PZ
     * leaves stale for a zombie on its shared-skeleton path (not blending, beyond the 20 nearest), so
     * its hit capsules stood in an older pose than the body drawn and bullets went through heads
     * (user's report of 21.10.2026, item 8). The rebuilt transform only where there are none yet.
     */
    static Matrix4f drawnBone(AnimationPlayer animation,int index) {
        Matrix4f drawn=index<animation.getModelTransformsCount()?animation.getModelTransformAt(index):null;
        return drawn!=null?new Matrix4f(drawn):animation.getBoneModelTransform(index,new Matrix4f());
    }
    private static Vec3 bonePoint(ShotSnapshot shot,IsoGameCharacter target,Matrix4f m,Vec3 local) {
        Vector3 p=new Vector3(m.m00*local.x()+m.m01*local.y()+m.m02*local.z()+m.m03,
                m.m10*local.x()+m.m11*local.y()+m.m12*local.z()+m.m13,
                m.m20*local.x()+m.m21*local.y()+m.m22*local.z()+m.m23);
        // Keep the large world origin out of the float native transform. It is
        // added in double only after the local vector has been transformed.
        Model.vectorToWorldCoords(0,0,0,target.getAnimationPlayer().getRenderedAngle(),p);
        if (!Float.isFinite(p.x) || !Float.isFinite(p.y) || !Float.isFinite(p.z)) return null;
        return metric(shot,(double)target.getX()+p.x,(double)target.getY()+p.y,(double)target.getZ()+p.z);
    }
    private static boolean finite(Matrix4f m) {
        return Float.isFinite(m.m00) && Float.isFinite(m.m01) && Float.isFinite(m.m02) && Float.isFinite(m.m03)
                && Float.isFinite(m.m10) && Float.isFinite(m.m11) && Float.isFinite(m.m12) && Float.isFinite(m.m13)
                && Float.isFinite(m.m20) && Float.isFinite(m.m21) && Float.isFinite(m.m22) && Float.isFinite(m.m23);
    }
    private static boolean finite(Matrix3f m) {
        return Float.isFinite(m.m00) && Float.isFinite(m.m01) && Float.isFinite(m.m02)
                && Float.isFinite(m.m10) && Float.isFinite(m.m11) && Float.isFinite(m.m12)
                && Float.isFinite(m.m20) && Float.isFinite(m.m21) && Float.isFinite(m.m22);
    }
    private static boolean finite(Vector3f v) {
        return v!=null && Float.isFinite(v.x) && Float.isFinite(v.y) && Float.isFinite(v.z);
    }
    private static boolean finite(double v) { return Double.isFinite(v); }
    /** Vehicle script areas are interaction rectangles, NOT measured panels.
     * Their projections and vertical split below are explicit project proxies.
     * Window glass of the vanilla meshes is measured (ShotVehicleGlass): real panes, and the
     * shell open where a pane lies behind it. Unassigned shell faces remain UNKNOWN_STOP. */
    private void vehicle(List<Collider> out,ShotSnapshot shot,Segment segment,BaseVehicle vehicle) {
        VehicleScript script=vehicle.getScript();if (script==null) throw new IllegalStateException("Vehicle has no script");
        WorldSimulation sim=WorldSimulation.instance;
        if (sim==null) throw new IllegalStateException("Vehicle physics unavailable");
        Transform transform=vehicle.getWorldTransform(new Transform());
        Vector3f ext=script.getExtents(),centre=script.getCenterOfMassOffset();
        // A vehicle whose transform or script dimensions are not finite has no place in the
        // shot's frame: skipped, never fatal (a vehicle spawned before the server positioned it).
        if (transform==null || !placed(transform.origin.x,transform.origin.y,transform.origin.z)
                || transform.basis==null || !finite(transform.basis)
                || !finite(ext) || !finite(centre) || !finite(sim.offsetX) || !finite(sim.offsetY)) {
            if (!readOnly(shot)) unplacedVehicle(vehicle,"transform-or-extents");
            return;
        }
        if (!positive(ext.x) || !positive(ext.y) || !positive(ext.z)) throw new IllegalStateException("Invalid vehicle extents");
        Vec3 min=new Vec3(centre.x-ext.x/2,centre.y-ext.y/2,centre.z-ext.z/2);
        Vec3 max=new Vec3(centre.x+ext.x/2,centre.y+ext.y/2,centre.z+ext.z/2);
        Vec3 broadMin=min,broadMax=max;
        for (int i=0;i<script.getWheelCount();i++) {
            var wheel=script.getWheel(i);Vector3f p=wheel.getOffset(),model=script.getModelOffset();
            if (!finite(p) || !finite(model)) continue;
            float half=wheel.width*script.getModelScale()/2,r=wheel.radius;
            if (!positive(half) || !positive(r)) continue;
            broadMin=new Vec3(Math.min(broadMin.x(),p.x+model.x-half),Math.min(broadMin.y(),p.y+model.y-r),Math.min(broadMin.z(),p.z+model.z-r));
            broadMax=new Vec3(Math.max(broadMax.x(),p.x+model.x+half),Math.max(broadMax.y(),p.y+model.y+r),Math.max(broadMax.z(),p.z+model.z+r));
        }
        // Measured glass and cabin can stand out of the script box (crash-deformed meshes): they widen the broad phase.
        ShotVehicleGlass.Measured glass=ShotVehicleGlass.measured(script);
        if (glass!=null) {
            broadMin=new Vec3(Math.min(broadMin.x(),glass.min().x()),Math.min(broadMin.y(),glass.min().y()),Math.min(broadMin.z(),glass.min().z()));
            broadMax=new Vec3(Math.max(broadMax.x(),glass.max().x()),Math.max(broadMax.y(),glass.max().y()),Math.max(broadMax.z(),glass.max().z()));
        }
        if (new VehicleShape(new Box(broadMin,broadMax),shot,transform,sim).intersect(segment.from(),segment.to())==null) return;
        ownerPieces.out=out;ownerPieces.shot=shot;ownerPieces.transform=transform;ownerPieces.sim=sim;ownerPieces.vehicle=vehicle;
        try { pieces(vehicle,script,ext,centre,min,max,glass,ownerPieces); }
        finally { ownerPieces.out=null;ownerPieces.shot=null;ownerPieces.transform=null;ownerPieces.sim=null;ownerPieces.vehicle=null; }
    }
    /**
     * Receives the pieces of a vehicle's shot geometry: the part (or null with the identity suffix of a shell or fixed-glass
     * piece of the vehicle), the vehicle-local shape, the material and the proof. W10 glasspass traces PZ's own shot
     * against the same pieces (ShotVehicleGlassPass).
     */
    public interface VehiclePieces {
        void piece(VehiclePart part,String suffix,Shape local,String material,String proof);
    }
    /** The owner path's sink: each piece one world collider of the current shot (one per instance, reused). */
    private final class OwnerPieces implements VehiclePieces {
        List<Collider> out;ShotSnapshot shot;Transform transform;WorldSimulation sim;BaseVehicle vehicle;
        @Override public void piece(VehiclePart part,String suffix,Shape local,String material,String proof) {
            out.add(world(part!=null?targets.identity(part):targets.identity(vehicle)+suffix,TargetKind.VEHICLE,
                    new VehicleShape(local,shot,transform,sim),material,proof));
        }
    }
    private final OwnerPieces ownerPieces=new OwnerPieces();
    private static final String[] SHELL_FACES={"/shell/0/false","/shell/0/true","/shell/1/false","/shell/1/true","/shell/2/false","/shell/2/true"};
    private static final java.util.Map<String,String> FIXED_GLASS=new java.util.HashMap<>();
    static { for (String zone:ShotVehicleGlassData.ZONES) FIXED_GLASS.put(zone,"/glass/"+zone); }
    /**
     * W10 glasspass: the owner path's pieces of a vehicle as it stands now (part states included: a broken, removed or open
     * pane is an opening), for PZ's own shot. False when the script has no usable box (the owner path skips it too).
     */
    public static boolean pieces(BaseVehicle vehicle,VehiclePieces sink) {
        VehicleScript script=vehicle==null?null:vehicle.getScript();
        if (script==null || vehicle.getParts()==null) return false;
        Vector3f ext=script.getExtents(),centre=script.getCenterOfMassOffset();
        if (!finite(ext) || !finite(centre) || !positive(ext.x) || !positive(ext.y) || !positive(ext.z)) return false;
        Vec3 min=new Vec3(centre.x-ext.x/2,centre.y-ext.y/2,centre.z-ext.z/2);
        Vec3 max=new Vec3(centre.x+ext.x/2,centre.y+ext.y/2,centre.z+ext.z/2);
        pieces(vehicle,script,ext,centre,min,max,ShotVehicleGlass.measured(script),sink);
        return true;
    }
    /** Every piece of a vehicle's shot geometry in vehicle-local cells (ShotContactsPz.vehicle before W10 glasspass). */
    private static void pieces(BaseVehicle vehicle,VehicleScript script,Vector3f ext,Vector3f centre,Vec3 min,Vec3 max,
            ShotVehicleGlass.Measured glass,VehiclePieces sink) {
        float lowerTopY=max.y(),cabinMinZ=min.z(),cabinMaxZ=max.z();
        boolean splitShell=false;
        int shapeCount=script.getPhysicsShapeCount();
        if (shapeCount>0) {
            float minBottom=Float.POSITIVE_INFINITY;
            for (int i=0;i<shapeCount;i++) {
                var ps=script.getPhysicsShape(i);
                if (ps==null || ps.type!=1) continue;
                Vector3f so=ps.getOffset(),se=ps.getExtents();
                if (so==null || se==null || se.x<0.5f*ext.x) continue;
                minBottom=Math.min(minBottom,so.y-se.y*0.5f);
            }
            if (Float.isFinite(minBottom)) {
                float tol=0.08f*script.getModelScale(),bestZ=-1f,chassisTop=max.y();
                for (int i=0;i<shapeCount;i++) {
                    var ps=script.getPhysicsShape(i);
                    if (ps==null || ps.type!=1) continue;
                    Vector3f so=ps.getOffset(),se=ps.getExtents();
                    if (so==null || se==null || se.x<0.5f*ext.x) continue;
                    if (so.y-se.y*0.5f<=minBottom+tol && se.z>bestZ) {
                        bestZ=se.z;chassisTop=so.y+se.y*0.5f;
                    }
                }
                if (bestZ>0 && chassisTop<max.y()-0.02f*script.getModelScale()) {
                    lowerTopY=Math.clamp(chassisTop,min.y(),max.y());
                    float cMinZ=Float.POSITIVE_INFINITY,cMaxZ=Float.NEGATIVE_INFINITY;
                    for (int i=0;i<shapeCount;i++) {
                        var ps=script.getPhysicsShape(i);
                        if (ps==null || ps.type!=1) continue;
                        Vector3f so=ps.getOffset(),se=ps.getExtents(),sr=ps.getRotate();
                        if (so==null || se==null || se.x<0.5f*ext.x) continue;
                        double rx=sr==null?0:Math.toRadians(sr.x);
                        float halfY=(float)(Math.abs(Math.cos(rx))*se.y*0.5+Math.abs(Math.sin(rx))*se.z*0.5);
                        float halfZ=(float)(Math.abs(Math.sin(rx))*se.y*0.5+Math.abs(Math.cos(rx))*se.z*0.5);
                        if (so.y+halfY>lowerTopY+0.01f*script.getModelScale()) {
                            cMinZ=Math.min(cMinZ,so.z-halfZ);cMaxZ=Math.max(cMaxZ,so.z+halfZ);
                        }
                    }
                    if (cMinZ<cMaxZ) {
                        cabinMinZ=Math.max(min.z(),cMinZ);cabinMaxZ=Math.min(max.z(),cMaxZ);splitShell=true;
                    }
                }
            }
        }
        float panelSplit=splitShell?lowerTopY:centre.y;
        VehicleParts parts=vehicle.getParts();
        if (parts==null) throw new IllegalStateException("Vehicle parts unavailable");
        ArrayList<Face> apertures=new ArrayList<>();
        java.util.Set<String> windows=new java.util.HashSet<>();
        if (glass!=null) for (int i=0;i<parts.getPartCount();i++) if (parts.getPartByIndex(i).getWindow()!=null) windows.add(parts.getPartByIndex(i).getId());
        for (int i=0;i<parts.getPartCount();i++) {
            VehiclePart part=parts.getPartByIndex(i);String kind=vehicleKind(part,parts,script);
            if (kind==null) continue;
            ShotVehicleGlass.Pane pane=glass==null || !kind.equals("window")?null:glass.pane(part.getId(),windows);
            if (pane!=null) {
                // The pane where the mesh draws it; gone, open or in an open door: nothing (the shell stays open there).
                if (ShotVehicleGlass.intact(part)) sink.piece(part,null,pane.shape(),
                        "vehicle-window|b5="+ShotWorldMaterials.vehicle("window"),"PZ.vehicle-script="+script.getFullName()+";part="+part.getId()
                        +";condition="+part.getCondition()+";state=installed;measured-glass="+glass.key()+"/"+pane.zone());
                continue;
            }
            String reason="proxy-area-OBB;proxy-glass-split="+(splitShell?"physics-chassis-top":"native-centerOfMassOffset.y")+";not-measured-panel";
            Shape local;
            if (kind.equals("tyre")) {
                var wheel=script.getWheel(part.getWheelIndex());Vector3f p=wheel.getOffset(),model=script.getModelOffset();
                // Loaded() scales wheel offset/radius, but leaves width unscaled.
                float half=wheel.width*script.getModelScale()/2,r=wheel.radius;
                if (!positive(half) || !positive(r)) continue;
                local=new Box(new Vec3(p.x+model.x-half,p.y+model.y-r,p.z+model.z-r),
                        new Vec3(p.x+model.x+half,p.y+model.y+r,p.z+model.z+r));
                reason="proxy-wheel-OBB;native-offset+modelOffset/radius/width;no-steering-or-suspension-pose";
            } else {
                var area=script.getAreaById(part.getArea());
                if (area==null || !positive(area.w) || !positive(area.h)) continue;
                Face face=vehicleFace(area,min,max,cabinMinZ,cabinMaxZ,panelSplit,kind.equals("window"));
                if (face==null) continue;
                // A missing/broken/open pane still defines an aperture. It must
                // not be filled again by the unknown aggregate body envelope.
                apertures.add(face);
                local=face;
                // A door panel proxy (min.y to the split) is open where measured glass dips below the split behind it.
                if (glass!=null && kind.equals("body")) local=ShotVehicleGlass.shell(face,
                        ShotContacts.get(face.min(),face.axis())>=ShotContacts.get(new Vec3(centre.x,centre.y,centre.z),face.axis())?1:-1,List.of(),glass);
                if (kind.equals("engine")) {
                    float depth=face.axis()==0?area.w:area.h;
                    Vec3 lo=face.min(),hi=face.max();
                    if (face.axis()==0) {
                        lo=new Vec3(Math.max(min.x(),face.coordinate()-depth),lo.y(),lo.z());
                        hi=new Vec3(Math.min(max.x(),face.coordinate()+depth),hi.y(),hi.z());
                    } else {
                        lo=new Vec3(lo.x(),lo.y(),Math.max(min.z(),face.coordinate()-depth));
                        hi=new Vec3(hi.x(),hi.y(),Math.min(max.z(),face.coordinate()+depth));
                    }
                    local=new Box(lo,hi);
                }
            }
            if (!vehicleInstalled(part)) continue;
            String preset=ShotWorldMaterials.vehicle(kind),state="installed";
            VehicleWindow window=part.getWindow();
            if (window!=null) {
                VehiclePart parent=part.getParent();
                if (window.isDestroyed() || part.getCondition()<=0 || window.isOpen() && window.getOpenDelta()>=1
                        || parent!=null && !vehicleInstalled(parent)) continue;
                if (window.isOpen() || !Float.isFinite(window.getOpenDelta()) || window.getOpenDelta()!=0) { preset=null;state="partial-or-invalid-window-pose"; }
                if (parent!=null && parent.getDoor()!=null && parent.getDoor().isOpen()) { preset=null;state="open-parent-door-pose-unknown"; }
            } else if (part.getDoor()!=null && part.getDoor().isOpen()) { preset=null;state="open-door-pose-unknown"; }
            // Zero condition does not prove a metal panel/engine/tyre vanished.
            sink.piece(part,null,local,
                    "vehicle-"+kind+(preset==null?"":"|b5="+preset),
                    "PZ.vehicle-script="+script.getFullName()+";part="+part.getId()+";condition="+part.getCondition()+";state="+state+";"+reason);
        }
        if (glass!=null) for (ShotVehicleGlass.Pane pane:glass.fixed(windows)) {
            // Glass the mesh draws without a window part (fixed rear quarters): Tarkov glass, nothing to break.
            String suffix=FIXED_GLASS.get(pane.zone());
            sink.piece(null,suffix!=null?suffix:"/glass/"+pane.zone(),pane.shape(),
                    "vehicle-window|b5="+ShotWorldMaterials.vehicle("window"),"PZ.vehicle-script="+script.getFullName()+";fixed-glass="+glass.key()+"/"+pane.zone());
        }
        List<Face> frozenApertures=List.copyOf(apertures);
        String shellProof="conservative:PZ.vehicle-script="+script.getFullName()+";unassigned-shell;proxy-OBB;no-material-evidence";
        if (!splitShell) {
            for (int axis=0;axis<3;axis++) for (boolean high:List.of(false,true)) {
                float coordinate=(float)ShotContacts.get(high?max:min,axis);
                Vec3 lo=axis==0?new Vec3(coordinate,min.y(),min.z()):axis==1?new Vec3(min.x(),coordinate,min.z()):new Vec3(min.x(),min.y(),coordinate);
                Vec3 hi=axis==0?new Vec3(coordinate,max.y(),max.z()):axis==1?new Vec3(max.x(),coordinate,max.z()):new Vec3(max.x(),max.y(),coordinate);
                Face face=new Face(axis,coordinate,lo,hi);
                sink.piece(null,SHELL_FACES[axis*2+(high?1:0)],ShotVehicleGlass.shell(face,high?1:-1,frozenApertures,glass),
                        "vehicle-unknown",shellProof);
            }
            return;
        }
        shellFace(sink,frozenApertures,glass,shellProof,"/shell/low-x0",-1,new Face(0,min.x(),new Vec3(min.x(),min.y(),min.z()),new Vec3(min.x(),panelSplit,max.z())));
        shellFace(sink,frozenApertures,glass,shellProof,"/shell/low-x1",1,new Face(0,max.x(),new Vec3(max.x(),min.y(),min.z()),new Vec3(max.x(),panelSplit,max.z())));
        if (glass==null) shellFace(sink,frozenApertures,glass,shellProof,"/shell/cab-x0",-1,new Face(0,min.x(),new Vec3(min.x(),panelSplit,cabinMinZ),new Vec3(min.x(),max.y(),cabinMaxZ)));
        if (glass==null) shellFace(sink,frozenApertures,glass,shellProof,"/shell/cab-x1",1,new Face(0,max.x(),new Vec3(max.x(),panelSplit,cabinMinZ),new Vec3(max.x(),max.y(),cabinMaxZ)));
        shellFace(sink,frozenApertures,glass,shellProof,"/shell/low-z0",-1,new Face(2,min.z(),new Vec3(min.x(),min.y(),min.z()),new Vec3(max.x(),panelSplit,min.z())));
        shellFace(sink,frozenApertures,glass,shellProof,"/shell/low-z1",1,new Face(2,max.z(),new Vec3(min.x(),min.y(),max.z()),new Vec3(max.x(),panelSplit,max.z())));
        if (glass==null) shellFace(sink,frozenApertures,glass,shellProof,"/shell/cab-z0",-1,new Face(2,cabinMinZ,new Vec3(min.x(),panelSplit,cabinMinZ),new Vec3(max.x(),max.y(),cabinMinZ)));
        if (glass==null) shellFace(sink,frozenApertures,glass,shellProof,"/shell/cab-z1",1,new Face(2,cabinMaxZ,new Vec3(min.x(),panelSplit,cabinMaxZ),new Vec3(max.x(),max.y(),cabinMaxZ)));
        shellFace(sink,frozenApertures,glass,shellProof,"/shell/bottom",-1,new Face(1,min.y(),new Vec3(min.x(),min.y(),min.z()),new Vec3(max.x(),min.y(),max.z())));
        if (glass==null) shellFace(sink,frozenApertures,glass,shellProof,"/shell/roof",1,new Face(1,max.y(),new Vec3(min.x(),max.y(),cabinMinZ),new Vec3(max.x(),max.y(),cabinMaxZ)));
        // Measured mesh: its cabin above the split replaces the box's cabin sides, ends and roof (still an unknown stop).
        else sink.piece(null,"/shell/cabin",glass.cabin(panelSplit),
                "vehicle-unknown","conservative:PZ.vehicle-script="+script.getFullName()+";mesh-cabin="+glass.key()+";no-material-evidence");
        if (cabinMinZ>min.z()+1e-4f) shellFace(sink,frozenApertures,glass,shellProof,"/shell/deck-rear",1,
                new Face(1,panelSplit,new Vec3(min.x(),panelSplit,min.z()),new Vec3(max.x(),panelSplit,cabinMinZ)));
        if (cabinMaxZ<max.z()-1e-4f) shellFace(sink,frozenApertures,glass,shellProof,"/shell/deck-front",1,
                new Face(1,panelSplit,new Vec3(min.x(),panelSplit,cabinMaxZ),new Vec3(max.x(),panelSplit,max.z())));
    }
    private static void shellFace(VehiclePieces sink,List<Face> apertures,ShotVehicleGlass.Measured glass,String proof,String suffix,
            int outward,Face face) {
        sink.piece(null,suffix,ShotVehicleGlass.shell(face,outward,apertures,glass),"vehicle-unknown",proof);
    }
    public static boolean vehicleInstalled(VehiclePart part) {
        var types=part.getItemType();return types==null || types.isEmpty() || part.getInventoryItem()!=null;
    }
    private static boolean positive(float value) { return Float.isFinite(value) && value>0; }
    private static String vehicleKind(VehiclePart part,VehicleParts parts,VehicleScript script) {
        if (part.getWindow()!=null) return "window";
        if (part==parts.getEngine()) return "engine";
        int wheel=part.getWheelIndex();
        if (wheel>=0 && wheel<script.getWheelCount() && part.getId().equals("Tire"+script.getWheel(wheel).getId())) return "tyre";
        return part.getDoor()!=null?"body":null;
    }
    static Face vehicleFace(VehicleScript.Area a,Vec3 min,Vec3 max,float split,boolean window) {
        return vehicleFace(a,min,max,min.z(),max.z(),split,window);
    }
    static Face vehicleFace(VehicleScript.Area a,Vec3 min,Vec3 max,float cabinMinZ,float cabinMaxZ,float split,boolean window) {
        float cx=(min.x()+max.x())/2,cz=(min.z()+max.z())/2;
        int axis=Math.abs((a.x-cx)/(max.x()-min.x()))>Math.abs((a.y-cz)/(max.z()-min.z()))?0:2;
        float zLo=window?cabinMinZ:min.z(),zHi=window?cabinMaxZ:max.z();
        float coordinate=axis==0?(a.x<cx?min.x():max.x()):(a.y<cz?zLo:zHi);
        float low=window?split:min.y(),high=window?max.y():split;
        float x0=axis==0?coordinate:Math.max(min.x(),a.x-a.w/2),x1=axis==0?coordinate:Math.min(max.x(),a.x+a.w/2);
        float z0=axis==2?coordinate:Math.max(zLo,a.y-a.h/2),z1=axis==2?coordinate:Math.min(zHi,a.y+a.h/2);
        return x0>x1 || z0>z1?null:new Face(axis,coordinate,new Vec3(x0,low,z0),new Vec3(x1,high,z1));
    }
    record VehicleShell(Face face,List<Face> apertures) implements Shape {
        @Override public Hit intersect(Vec3 from,Vec3 to) {
            Hit hit=face.intersect(from,to);if (hit==null) return null;
            Vec3 point=from.plus(to.minus(from).times((float)hit.fraction()));
            for (Face aperture:apertures) if (aperture.axis()==face.axis() && Math.abs(aperture.coordinate()-face.coordinate())<1e-4f
                    && point.x()>=aperture.min().x()-1e-5 && point.x()<=aperture.max().x()+1e-5
                    && point.y()>=aperture.min().y()-1e-5 && point.y()<=aperture.max().y()+1e-5
                    && point.z()>=aperture.min().z()-1e-5 && point.z()<=aperture.max().z()+1e-5) return null;
            return hit;
        }
    }
    static final class VehicleShape implements Shape {
        final Shape local;final Transform rotation,inverse;final Vec3 origin;
        VehicleShape(Shape local,ShotSnapshot shot,Transform transform,WorldSimulation sim) {
            this.local=local;rotation=new Transform(transform);rotation.origin.set(0,0,0);
            inverse=new Transform(rotation);inverse.inverse();Pz p=shot.pose().muzzlePz();
            origin=new Vec3((float)(((double)transform.origin.x+sim.offsetX-p.x())*TarkovBallistics.METRES_PER_CELL),
                    (float)(transform.origin.y*TarkovBallistics.METRES_PER_CELL-p.z()*TarkovBallistics.METRES_PER_LEVEL),
                    (float)(((double)transform.origin.z+sim.offsetY-p.y())*TarkovBallistics.METRES_PER_CELL));
        }
        Vec3 local(Vec3 point) {
            Vec3 delta=point.minus(origin).times((float)(1/TarkovBallistics.METRES_PER_CELL));
            Vector3f p=new Vector3f(delta.x(),delta.y(),delta.z());inverse.transform(p);return new Vec3(p.x,p.y,p.z);
        }
        @Override public boolean contains(Vec3 point) { return local.contains(local(point)); }
        @Override public Hit intersect(Vec3 from,Vec3 to) {
            Hit hit=local.intersect(local(from),local(to));if (hit==null) return null;
            Vector3f n=new Vector3f(hit.normal().x(),hit.normal().y(),hit.normal().z());rotation.basis.transform(n);
            return new Hit(hit.fraction(),ShotContacts.normal(new Vec3(n.x,n.y,n.z),new Vec3(0,1,0)));
        }
    }
    static Collider world(String target,TargetKind kind,Shape shape,String material,String proof) {
        return new Collider(target,kind,shape,-1,-1,material,proof);
    }
    static Vec3 metric(ShotSnapshot shot,double x,double y,double z) {
        Pz origin=shot.pose().muzzlePz();
        return new Vec3((float)((x-origin.x())*TarkovBallistics.METRES_PER_CELL),
                (float)((z-origin.z())*TarkovBallistics.METRES_PER_LEVEL),(float)((y-origin.y())*TarkovBallistics.METRES_PER_CELL));
    }
    private static Box tile(ShotSnapshot shot,int x,int y,int z) {
        return new Box(metric(shot,x,y,z),metric(shot,x+1,y+1,z+1));
    }
    private static Box subBox(ShotSnapshot shot,IsoGridSquare sq,double x0,double x1,double y0,double y1,double z0,double z1) {
        return new Box(metric(shot,sq.getX()+x0,sq.getY()+y0,sq.getZ()+z0),
                metric(shot,sq.getX()+x1,sq.getY()+y1,sq.getZ()+z1));
    }
    private static Face offsetFace(ShotSnapshot shot,IsoGridSquare sq,int axis,double cellOffset,double u0,double u1,double v0,double v1) {
        Vec3 min=metric(shot,sq.getX()+(axis==0?cellOffset:0),sq.getY()+(axis==2?cellOffset:0),sq.getZ());
        Vec3 max=metric(shot,sq.getX()+(axis==0?cellOffset:1),sq.getY()+(axis==2?cellOffset:1),sq.getZ()+1);
        float coordinate=(float)ShotContacts.get(min,axis);
        return ShotContacts.subFace(new Face(axis,coordinate,min,max),u0,u1,v0,v1);
    }
    private static Face face(ShotSnapshot shot,IsoGridSquare square,int axis) {
        Vec3 min=metric(shot,square.getX(),square.getY(),square.getZ());
        Vec3 max=metric(shot,square.getX()+1,square.getY()+1,square.getZ()+1);
        float coordinate=(float)ShotContacts.get(min,axis);
        if (axis==0) max=new Vec3(coordinate,max.y(),max.z());
        else if (axis==1) max=new Vec3(max.x(),coordinate,max.z());
        else max=new Vec3(max.x(),max.y(),coordinate);
        return new Face(axis,coordinate,min,max);
    }
}
