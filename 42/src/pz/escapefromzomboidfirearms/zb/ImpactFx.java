package pz.escapefromzomboidfirearms.zb;

import zombie.GameTime;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoWorld;
import pz.escapefromzomboidfirearms.zb.ImpactMarks.Event;
import pz.escapefromzomboidfirearms.zb.ShotPoseSnapshot.Pz;

/** Submission only; the existing VP pass owns depth, HDR/fallback and GL state. */
public final class ImpactFx {
    public static volatile long frames,quads,sprites,streaks,audio;
    public static volatile long ticks,isoFrames,isoQuads,isoSprites;
    /** Why an isometric mark was not drawn (QA): behind, unlit, off-screen, no texture. */
    public static volatile long isoFar,isoBack,isoUnloaded,isoNoSquare,isoUnlit,isoNoTexture;
    private static Object cell;
    private ImpactFx() {}

    public static boolean loaded(Event e) {
        IsoCell c=IsoWorld.instance==null?null:IsoWorld.instance.getCell();
        if (c==null) return false;
        Pz p=e.point();
        if (c.getGridSquare((int)Math.floor(p.x()),(int)Math.floor(p.y()),(int)Math.floor(p.z()))!=null) return true;
        Pz front=p.offset(e.normal().times((float)ImpactMarks.OFFSET_METRES));
        return c.getGridSquare((int)Math.floor(front.x()),(int)Math.floor(front.y()),(int)Math.floor(front.z()))!=null;
    }
    /** Physical game clock: pause does not age marks; a new world cannot inherit them. */
    public static void tick() {
        if (!ImpactMarks.enabled || ImpactMarks.failed) return;
        try {
            Object now=IsoWorld.instance==null?null:IsoWorld.instance.getCell();
            if (cell!=now) { ImpactMarks.STORE.clear();cell=now; }
            if (now==null || GameTime.isGamePaused()) return;
            float dt=GameTime.getInstance().getPhysicsSecondsSinceLastUpdate();
            ImpactMarks.STORE.advance(dt,ImpactFx::loaded);
            ticks++;
            for (int i=0;i<32;i++) {
                Event e=ImpactMarks.STORE.sound();if (e==null) break;
                if (!loaded(e)) continue;
                String name=e.outcome().equals("RICOCHET")?"TW_Impact_Ricochet":ImpactData.profile(e.preset()).sound();
                var p=e.point();var emitter=IsoWorld.instance.getFreeEmitter((float)p.x(),(float)p.y(),(float)p.z());
                // The cosmetic event is already relayed. playSound() would send
                // a second native PlaySound packet on a client.
                if (emitter!=null && emitter.playSoundImpl(name,(zombie.iso.IsoObject)null)!=0) audio++;
            }
        } catch (Throwable t) { ImpactMarks.fail(t); }
    }
    /**
     * Performance pass 23.10.2026 (every frame for 120 s after a shot): the store's snapshot into reused arrays (as
     * marks()/particles() under its lock), positions and light into scratch rows (ViewpointPass reads its arguments at
     * once), a mark's corners once (ImpactMarks.Mark), one load test per contact (its particles lie together) and the
     * cheap tests first - every test is a pure read of this frame, so the same ones pass. Main thread only.
     */
    private static ImpactMarks.Mark[] marks=new ImpactMarks.Mark[64];
    private static ImpactMarks.Particle[] particles=new ImpactMarks.Particle[256];
    private static final double[] AT=new double[3],TAIL=new double[3],POINTS=new double[8],EYE=new double[6];
    private static final float[] LIT=new float[3],WHITE={1,1,1},ALPHAS=new float[4],XY=new float[8];
    private static Event loadedEvent;
    private static boolean loadedValue;
    private static boolean loadedOnce(Event e) {
        if (e!=loadedEvent) { loadedEvent=e;loadedValue=loaded(e); }
        return loadedValue;
    }
    private static float[] light(double x,double y,double z) {
        int rgb=ViewpointBridge.cellLight((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z));
        float[] out=LIT;
        if (rgb>=0) { out[0]=(rgb&255)/255f;out[1]=(rgb>>>8&255)/255f;out[2]=(rgb>>>16&255)/255f;return out; }
        var square=IsoWorld.instance.getCell().getGridSquare((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z));
        var info=square==null?null:square.getLightInfo(0);
        if (info==null) { out[0]=out[1]=out[2]=0; } else { out[0]=info.r;out[1]=info.g;out[2]=info.b; }
        return out;
    }
    private static boolean near(Pz p) { return near(p.x(),p.y(),p.z()); }
    private static boolean near(double x,double y,double z) {
        IsoPlayer player=IsoPlayer.getInstance();
        if (player==null) return false;
        double dx=(x-player.getX())*TarkovBallistics.METRES_PER_CELL,dy=(y-player.getY())*TarkovBallistics.METRES_PER_CELL;
        double dz=(z-player.getZ())*TarkovBallistics.METRES_PER_LEVEL;
        return dx*dx+dy*dy+dz*dz<=60*60;
    }
    private static void forget(Object[] rows) {
        for (int i=0;i<rows.length && rows[i]!=null;i++) rows[i]=null;
        loadedEvent=null;
    }
    /**
     * View-ray depth bias (scene units) for a Viewpoint mark, or -1 when the camera stands behind the surface face.
     * Ballistic wall/door/window planes lie on the cell grid edge, whereas Viewpoint extrudes 3D wall/door meshes
     * (tileGeometry boxes +0.047, WALL_ART_W +0.03125, WALL_ART_N +0.0625) in front of the edge; shifting along the
     * camera ray keeps exact screen pixels while clearing the extruded face depth without bleeding through back faces.
     */
    static float fpBias(Pz center,pz.escapefromzomboidfirearms.zb.TarkovBallistics.Vec3 normal,
            double eyeX,double eyeY,double eyeZ) {
        double dx=(eyeX-center.x())*TarkovBallistics.METRES_PER_CELL;
        double dy=(eyeZ-center.z())*TarkovBallistics.METRES_PER_LEVEL;
        double dz=(eyeY-center.y())*TarkovBallistics.METRES_PER_CELL;
        double dot=dx*normal.x()+dy*normal.y()+dz*normal.z();
        if (!(dot>0)) return -1f;
        if (Math.abs(normal.y())>0.9f) return 0.015f;
        double dist=Math.sqrt(dx*dx+dy*dy+dz*dz);
        double cos=dist>1e-4?dot/dist:1.0;
        return (float)Math.min(0.22,0.095/Math.max(0.4,cos));
    }
    public static void afterWorld() {
        if (!ImpactMarks.enabled || ImpactMarks.failed || !ImpactMarks.STORE.visualsEnabled()
                || !ViewpointPass.accepting() || !ViewpointBridge.perspective()) return;
        try {
            double time=ImpactMarks.STORE.time;
            boolean hasEye=ViewpointPass.eye(EYE);
            if (!hasEye) {
                IsoPlayer p=IsoPlayer.getInstance();
                if (p!=null) { EYE[0]=p.getX();EYE[1]=p.getY();EYE[2]=p.getZ()+0.53;hasEye=true; }
            }
            marks=ImpactMarks.STORE.marks(marks);
            try {
                for (var mark:marks) {
                    if (mark==null) break;
                    if (!near(mark.center) || !loadedOnce(mark.event)) continue;
                    float bias=hasEye?fpBias(mark.center,mark.event.normal(),EYE[0],EYE[1],EYE[2]):0.095f;
                    if (bias<0f) continue;
                    float alpha=mark.alpha(time);float[] lit=light(mark.center.x(),mark.center.y(),mark.center.z());
                    ALPHAS[0]=ALPHAS[1]=ALPHAS[2]=ALPHAS[3]=alpha;
                    if (ViewpointPass.quad(mark.cornersView(),lit[0],lit[1],lit[2],ALPHAS,
                            mark.texture,ViewpointPass.ALPHA,0.008f,bias)) quads++;
                }
            } finally { forget(marks); }
            particles=ImpactMarks.STORE.particles(particles);
            try {
                double[] point=AT;
                for (var p:particles) {
                    if (p==null) break;
                    p.positionInto(time,point);if (!near(point[0],point[1],point[2]) || !loadedOnce(p.event)) continue;
                    float[] lit=p.source.additive()?WHITE:light(point[0],point[1],point[2]);
                    float diameter=p.diameter(time),alpha=p.alpha(time);
                    if (!(diameter>0 && alpha>0)) continue;
                    if (p.source.streak()) {
                        double[] tail=TAIL;p.positionInto(Math.max(p.born,time-1d/60),tail);
                        double[] points=POINTS;
                        points[0]=tail[0];points[1]=tail[1];points[2]=tail[2];points[3]=0;
                        points[4]=point[0];points[5]=point[1];points[6]=point[2];points[7]=1;
                        if (ViewpointPass.strip(points,2,p.source.r()*lit[0],p.source.g()*lit[1],p.source.b()*lit[2],alpha,0,
                                (float)(diameter/TarkovBallistics.METRES_PER_CELL),p.source.additive()?ViewpointPass.ADD:ViewpointPass.ALPHA,0.005f)) streaks++;
                    } else if (ViewpointPass.sprite(point[0],point[1],point[2],(float)(diameter/2/TarkovBallistics.METRES_PER_CELL),0,
                            p.source.r()*lit[0],p.source.g()*lit[1],p.source.b()*lit[2],alpha,p.texture,
                            p.source.additive()?ViewpointPass.ADD:ViewpointPass.ALPHA,0.005f,0)) sprites++;
                }
            } finally { forget(particles); }
            frames++;
        } catch (Throwable t) { ImpactMarks.fail(t); }
    }
    /** Native isometric world buffer, before scaling/compositing and the HUD. */
    public static float isoX(double x,double y,double z) {
        return zombie.iso.IsoUtils.XToScreen((float)x,(float)y,(float)z,0)-zombie.iso.IsoCamera.frameState.offX;
    }
    public static float isoY(double x,double y,double z) {
        return zombie.iso.IsoUtils.YToScreen((float)x,(float)y,(float)z,0)-zombie.iso.IsoCamera.frameState.offY;
    }
    public static boolean isoVisible(double x,double y,double z) {
        var c=IsoWorld.instance==null?null:IsoWorld.instance.getCell();
        var sq=c==null?null:c.getGridSquare((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z));
        return sq!=null && sq.isCanSee(zombie.iso.IsoCamera.frameState.playerIndex);
    }
    static boolean isoFront(pz.escapefromzomboidfirearms.zb.TarkovBallistics.Vec3 normal) {
        // Native YToScreen=(x+y)*16-z*96 (same TileScale). The camera is
        // above the south/east faces; convert its null vector to metric axes.
        return normal.x()+normal.z()+normal.y()*TarkovBallistics.METRES_PER_LEVEL/(3*TarkovBallistics.METRES_PER_CELL)>0;
    }
    public static boolean isoSprite(zombie.core.textures.Texture texture,double x,double y,double z,float pixels,
            float r,float g,float b,float alpha) {
        if (texture==null || !texture.isReady() || pixels<=0 || alpha<=0 || !isoVisible(x,y,z)) return false;
        float cx=isoX(x,y,z),cy=isoY(x,y,z),half=pixels*.5f;
        zombie.core.SpriteRenderer.instance.renderPoly(texture,cx-half,cy-half,cx+half,cy-half,
                cx+half,cy+half,cx-half,cy+half,r,g,b,alpha);
        return true;
    }
    public static void isometric() {
        if (!ImpactMarks.enabled || ImpactMarks.failed || !ImpactMarks.STORE.visualsEnabled() || ViewpointBridge.perspective()) return;
        try {
            isoFrames++;
            double time=ImpactMarks.STORE.time;
            int player=zombie.iso.IsoCamera.frameState.playerIndex;
            // Performance pass 23.10.2026: see afterWorld; the square of a mark's centre is looked up once (the
            // visibility test and its light used two lookups of the same square) and its image is kept by the mark.
            marks=ImpactMarks.STORE.marks(marks);
            try {
                var cell=IsoWorld.instance==null?null:IsoWorld.instance.getCell();
                for (var m:marks) {
                    if (m==null) break;
                    // Rear faces are hidden in PZ's fixed camera; never paint through a wall.  The entry face of
                    // a hole always points back at the shooter, and the isometric camera stands behind the
                    // shooter, so `front` is the visibility rule; the square's `isCanSee` flag is a renderer
                    // cut-away flag and is false for the wall square itself (W8 item 3: wall holes never drew).
                    if (!near(m.center)) {isoFar++;continue;}
                    if (!m.front) {isoBack++;continue;}
                    if (!loadedOnce(m.event)) {isoUnloaded++;continue;}
                    var sq=cell==null?null:cell.getGridSquare((int)Math.floor(m.center.x()),(int)Math.floor(m.center.y()),(int)Math.floor(m.center.z()));
                    if (sq==null) {isoNoSquare++;continue;}
                    // A square PZ has not lit for this player yet has no light info: the mark waits for it.
                    // It used to throw and turned every impact mark off for the session (user's log of
                    // 21.10.2026: "lit" is null), so later misses showed no mark at all.
                    var lit=sq.getLightInfo(player);
                    if (lit==null) {isoUnlit++;continue;}
                    var texture=(zombie.core.textures.Texture)m.isoTexture;
                    if (texture==null) m.isoTexture=texture=zombie.core.textures.Texture.getSharedTexture("media/textures/"+m.texture+".png");
                    if (texture==null || !texture.isReady()) {isoNoTexture++;continue;}
                    double[] corners=m.cornersView();float[] xy=XY;
                    for (int i=0;i<4;i++) {xy[2*i]=isoX(corners[3*i],corners[3*i+1],corners[3*i+2]);
                        xy[2*i+1]=isoY(corners[3*i],corners[3*i+1],corners[3*i+2]);}
                    zombie.core.SpriteRenderer.instance.renderPoly(texture,xy[0],xy[1],xy[2],xy[3],xy[4],xy[5],xy[6],xy[7],lit.r,lit.g,lit.b,m.alpha(time));
                    isoQuads++;
                }
            } finally { forget(marks); }
            particles=ImpactMarks.STORE.particles(particles);
            try {
                double[] pos=AT;
                for (var p:particles) {
                    if (p==null) break;
                    p.positionInto(time,pos);if (!near(pos[0],pos[1],pos[2]) || !loadedOnce(p.event)) continue;
                    var texture=(zombie.core.textures.Texture)p.isoTexture;
                    if (texture==null) p.isoTexture=texture=zombie.core.textures.Texture.getSharedTexture("media/textures/"+p.texture+".png");
                    if (isoSprite(texture,pos[0],pos[1],pos[2],(float)(p.diameter(time)/TarkovBallistics.METRES_PER_CELL*64),
                            p.source.r(),p.source.g(),p.source.b(),p.alpha(time))) isoSprites++;
                }
            } finally { forget(particles); }
        } catch (Throwable error) { ImpactMarks.fail(error); }
    }
    public static void reset() {
        ImpactMarks.STORE.clear();
        cell=IsoWorld.instance==null?null:IsoWorld.instance.getCell();
    }
}
