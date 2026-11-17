package pz.escapefromzomboidfirearms.zb;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;

/** Transient local identity registry shared by tracing and damage. C may subclass
 * this with its validated network identity resolver. IDs never name array slots,
 * and are never recycled after removal/reset. No live target is retained by a shot.
 */
public class ShotTargets {
    private final ReferenceQueue<Object> queue=new ReferenceQueue<>();
    private final HashMap<Ref,String> byObject=new HashMap<>();
    private final HashMap<String,Ref> byId=new HashMap<>();
    private long next;
    private static final class Ref extends WeakReference<Object> {
        final int hash;
        Ref(Object object,ReferenceQueue<Object> queue) { super(object,queue);hash=System.identityHashCode(object); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this==other || other instanceof Ref r && get()!=null && get()==r.get();
        }
    }
    private void drain() {
        Ref ref;
        while ((ref=(Ref)queue.poll())!=null) { String id=byObject.remove(ref);if (id!=null) byId.remove(id,ref); }
    }
    public synchronized String identity(Object object) {
        if (object==null) throw new IllegalArgumentException("Null target");
        drain();Ref lookup=new Ref(object,null);String existing=byObject.get(lookup);
        if (existing!=null) return existing;
        if (byObject.size()>=65536 || next==Long.MAX_VALUE) throw new IllegalStateException("Target identity capacity");
        String id="local:"+(++next);Ref ref=new Ref(object,queue);byObject.put(ref,id);byId.put(id,ref);return id;
    }
    public synchronized Object resolve(String identity) { drain();Ref ref=byId.get(identity);return ref==null?null:ref.get(); }
    public synchronized void clear() { byObject.clear();byId.clear();drain(); }
}
