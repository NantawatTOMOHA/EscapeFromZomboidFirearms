package pz.escapefromzomboidfirearms.zb;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Immutable vanilla inputs. Loaded explicitly by a future shot owner, never at class startup. */
public final class TarkovRecoilData {
    public static final String RESOURCE = "media/viewpoint/TW_Recoil.bin";
    public final String gunplayHash, curvesHash, playerName;
    public final Map<String,Object> root, player, weapons, modifiers, ammo, aiming, hard, stamina;
    public final List<Curve> curves;

    private TarkovRecoilData(String g, String c, List<Curve> curves, Map<String,Object> root) {
        gunplayHash=g; curvesHash=c; this.curves=List.copyOf(curves); this.root=root;
        playerName=(String)root.get("playerName"); player=map(root,"player");
        weapons=map(root,"weapons"); modifiers=map(root,"modifiers"); ammo=map(root,"ammo");
        aiming=map(root,"aiming"); hard=map(root,"hard"); stamina=map(root,"stamina");
        if (!"playersuperior".equals(playerName) || weapons.size()!=149 || modifiers.size()!=2118 || ammo.size()!=112)
            throw new IllegalArgumentException("incomplete recoil resource");
        for (Object entry:weapons.values()) {
            Map<String,Object> w=object(entry);
            if (!modifiers.containsKey(w.get("tpl")) || !ammo.containsKey(w.get("defaultAmmoTpl")))
                throw new IllegalArgumentException("unresolved weapon reference");
        }
    }

    public static TarkovRecoilData load(Path path) throws IOException { return decode(Files.readAllBytes(path)); }

    /** Installed entries are instances: equal tpl in distinct slots counts twice; the root is excluded. */
    public TarkovRecoil.Assembly assembly(String key, List<String> installed, boolean folded,
                                          float contentsMassKg, float hostStockRecoilDelta) {
        Map<String,Object> weapon=map(weapons,key), props=map(weapon,"props");
        float recoil=0, ergo=0, mass=number(props,"Weight");
        if (contentsMassKg<0) throw new IllegalArgumentException("negative contents mass");
        finite(contentsMassKg); finite(hostStockRecoilDelta);
        for (String tpl:installed) {
            Map<String,Object> part=map(modifiers,tpl);
            if (flag(part,"root")) throw new IllegalArgumentException("root in installed parts");
            if (!folded || !"Stock".equals(part.get("category"))) recoil+=number(part,"Recoil");
            ergo+=number(part,"Ergonomics"); mass+=number(part,"Weight");
        }
        float base=number(props,"Ergonomics");
        float total=base*(1+ergo/Math.max(1,base));
        mass+=contentsMassKg;
        return new TarkovRecoil.Assembly(key,installed,folded,recoil/100+hostStockRecoilDelta,
                Math.max(0,total),mass,mass*(1-(float)Math.sqrt(Math.max(0,total))/25));
    }

    public TarkovRecoil.Ammo ammunition(String tpl) {
        return new TarkovRecoil.Ammo(tpl,number(map(ammo,tpl),"ammoRec"));
    }

    public static TarkovRecoilData decode(byte[] bytes) throws IOException {
        if (bytes.length<113 || bytes.length>4_000_000) throw new IOException("recoil resource size");
        try (var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (!Arrays.equals(in.readNBytes(8),"TWRECOIL".getBytes(StandardCharsets.US_ASCII)) || in.readInt()!=1)
                throw new IOException("recoil resource version");
            int size=in.readInt();
            if (size<1 || size>4_000_000) throw new IOException("recoil payload size");
            String g=HexFormat.of().formatHex(in.readNBytes(32)), c=HexFormat.of().formatHex(in.readNBytes(32));
            byte[] expected=in.readNBytes(32), compressed=in.readAllBytes(), payload=new byte[size];
            Inflater inflater=new Inflater();
            try {
                inflater.setInput(compressed);
                int offset=0;
                while (!inflater.finished() && offset<size) {
                    int count=inflater.inflate(payload,offset,size-offset);
                    if (count==0) throw new IOException("truncated recoil compression");
                    offset+=count;
                }
                if (offset!=size || !inflater.finished() || inflater.getRemaining()!=0)
                    throw new IOException("recoil compression boundary");
            } finally { inflater.end(); }
            if (!MessageDigest.isEqual(expected,MessageDigest.getInstance("SHA-256").digest(payload)))
                throw new IOException("recoil payload hash");
            try (var body=new DataInputStream(new ByteArrayInputStream(payload))) {
                int count=count(body,4096); List<Curve> curves=new ArrayList<>();
                for (int i=0;i<count;i++) {
                    int pre=body.readInt(), post=body.readInt(), order=body.readInt(), n=count(body,4096);
                    List<Key> keys=new ArrayList<>();
                    for (int k=0;k<n;k++) keys.add(new Key(body.readFloat(),body.readFloat(),body.readFloat(),
                            body.readFloat(),body.readFloat(),body.readFloat(),body.readInt()));
                    curves.add(new Curve(pre,post,order,keys));
                }
                Map<String,Object> tree=object(read(body,curves,0));
                if (body.available()!=0) throw new IOException("recoil payload tail");
                return new TarkovRecoilData(g,c,curves,tree);
            }
        } catch (DataFormatException | GeneralSecurityException | IllegalArgumentException | ClassCastException ex) {
            throw new IOException("invalid recoil resource",ex);
        }
    }

    private static int count(DataInputStream in, int max) throws IOException {
        int n=in.readInt(); if (n<0 || n>max) throw new IOException("recoil count"); return n;
    }
    private static String string(DataInputStream in) throws IOException {
        int n=count(in,1_000_000); byte[] raw=in.readNBytes(n);
        if (raw.length!=n) throw new EOFException();
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
    }
    private static Object read(DataInputStream in, List<Curve> curves, int depth) throws IOException {
        if (depth>32) throw new IOException("recoil tree depth");
        return switch(in.readUnsignedByte()) {
            case 0 -> null;
            case 1 -> false;
            case 2 -> true;
            case 3 -> finite(in.readFloat());
            case 4 -> string(in);
            case 5 -> {
                int n=count(in,10000); List<Object> list=new ArrayList<>();
                for (int i=0;i<n;i++) list.add(read(in,curves,depth+1));
                yield Collections.unmodifiableList(list);
            }
            case 6 -> {
                int n=count(in,10000); Map<String,Object> map=new LinkedHashMap<>();
                for (int i=0;i<n;i++) {
                    String key=string(in);
                    if (map.containsKey(key)) throw new IOException("duplicate recoil field");
                    map.put(key,read(in,curves,depth+1));
                }
                yield Collections.unmodifiableMap(map);
            }
            case 7 -> {
                int i=in.readInt(); if (i<0 || i>=curves.size()) throw new IOException("recoil curve reference");
                yield curves.get(i);
            }
            default -> throw new IOException("recoil tree tag");
        };
    }

    @SuppressWarnings("unchecked")
    static Map<String,Object> object(Object o) {
        if (!(o instanceof Map<?,?>)) throw new IllegalArgumentException("expected recoil object");
        return (Map<String,Object>)o;
    }
    static Map<String,Object> map(Map<String,Object> o, String key) { return object(o.get(key)); }
    @SuppressWarnings("unchecked")
    static List<Object> list(Map<String,Object> o, String key) { return (List<Object>)o.get(key); }
    static float number(Map<String,Object> o, String key) { return (Float)Objects.requireNonNull(o.get(key),key); }
    static float number(Map<String,Object> o, String key, float fallback) { return o.containsKey(key)?number(o,key):fallback; }
    static boolean flag(Map<String,Object> o, String key) {
        Object v=o.get(key); return v instanceof Boolean b?b:v instanceof Float f && f!=0;
    }
    static Curve curve(Map<String,Object> o, String key) { return (Curve)Objects.requireNonNull(o.get(key),key); }
    static float finite(float f) {
        if (!Float.isFinite(f)) throw new IllegalArgumentException("nonfinite recoil input"); return f;
    }

    public record Key(float time, float value, float inSlope, float outSlope,
                      float inWeight, float outWeight, int weightedMode) {
        public Key {
            finite(time); finite(value); finite(inWeight); finite(outWeight);
            if (Float.isNaN(inSlope) || Float.isNaN(outSlope) || weightedMode<0 || weightedMode>3
                    || inWeight<0 || inWeight>1 || outWeight<0 || outWeight>1)
                throw new IllegalArgumentException("invalid recoil curve key");
        }
    }

    /** Serialized curve infinity: 0 ping-pong, 1 repeat, 2 clamp. Public WrapMode numbers differ. */
    public record Curve(int preInfinity, int postInfinity, int rotationOrder, List<Key> keys) {
        public Curve {
            keys=List.copyOf(keys);
            if (preInfinity<0 || preInfinity>2 || postInfinity<0 || postInfinity>2)
                throw new IllegalArgumentException("unknown serialized infinity mode");
            float last=Float.NEGATIVE_INFINITY;
            for (Key k:keys) {
                if (k.time()<=last) throw new IllegalArgumentException("unordered recoil curve");
                last=k.time();
            }
        }
        public float value(float time) {
            finite(time); if (keys.isEmpty()) return 0;
            Key first=keys.getFirst(), last=keys.getLast();
            if (keys.size()==1) return first.value();
            if (time<first.time() || time>last.time()) {
                int mode=time<first.time()?preInfinity:postInfinity;
                if (mode==2) return time<first.time()?first.value():last.value();
                double length=last.time()-first.time(), t=time-first.time();
                double period=mode==0?2*length:length;
                t=t-Math.floor(t/period)*period;
                if (mode==0 && t>length) t=period-t;
                time=(float)(first.time()+t);
            }
            for (int i=0;i<keys.size();i++) if (time==keys.get(i).time()) return keys.get(i).value();
            int i=0; while(i+1<keys.size() && time>keys.get(i+1).time()) i++;
            Key a=keys.get(i), b=keys.get(i+1);
            if (!Float.isFinite(a.outSlope()) || !Float.isFinite(b.inSlope())) return a.value();
            double span=b.time()-a.time(), t=(time-a.time())/span;
            boolean weighted=(a.weightedMode()&2)!=0 || (b.weightedMode()&1)!=0;
            if (!weighted) {
                double t2=t*t, t3=t2*t;
                return (float)((2*t3-3*t2+1)*a.value()+(t3-2*t2+t)*span*a.outSlope()
                        +(-2*t3+3*t2)*b.value()+(t3-t2)*span*b.inSlope());
            }
            double w1=(a.weightedMode()&2)!=0?a.outWeight():1.0/3;
            double w2=(b.weightedMode()&1)!=0?b.inWeight():1.0/3;
            double lo=0, hi=1;
            for (int k=0;k<56;k++) {
                double u=(lo+hi)/2;
                if (bezier(0,w1,1-w2,1,u)<t) lo=u; else hi=u;
            }
            return (float)bezier(a.value(),a.value()+span*w1*a.outSlope(),
                    b.value()-span*w2*b.inSlope(),b.value(),(lo+hi)/2);
        }
        private static double bezier(double a, double b, double c, double d, double t) {
            double s=1-t; return s*s*s*a+3*s*s*t*b+3*s*t*t*c+t*t*t*d;
        }
    }
}
