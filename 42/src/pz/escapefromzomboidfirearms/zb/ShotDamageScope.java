package pz.escapefromzomboidfirearms.zb;

import java.util.ArrayDeque;
import java.util.Objects;

/** Exact native wound receiver/weapon scope, standard Java for detached tests.
 * Unrelated/nested callbacks retain their own argument. Only one matching wound
 * body executes per accepted native Hit; -2 is an advice skip, never a PZ zone.
 */
public final class ShotDamageScope implements AutoCloseable {
    private static final ThreadLocal<ArrayDeque<ShotDamageScope>> STACK=ThreadLocal.withInitial(ArrayDeque::new);
    private final Object body,weapon;
    private final int part;
    private final Thread owner=Thread.currentThread();
    private boolean used,closed;
    public ShotDamageScope(Object body,Object weapon,int part) {
        this.body=Objects.requireNonNull(body);this.weapon=Objects.requireNonNull(weapon);
        if (part<0) throw new IllegalArgumentException("Unknown wound zone");
        this.part=part;STACK.get().push(this);
    }
    public static int woundPart(Object body,Object weapon,int original) {
        ArrayDeque<ShotDamageScope> stack=STACK.get();ShotDamageScope scope=stack.peek();
        if (scope==null) { STACK.remove();return original; }
        if (scope.body!=body || scope.weapon!=weapon) return original;
        if (scope.used) return -2;
        scope.used=true;return scope.part;
    }
    @Override public void close() {
        if (closed || Thread.currentThread()!=owner || STACK.get().peek()!=this) throw new IllegalStateException("Impact scope order/thread");
        ArrayDeque<ShotDamageScope> stack=STACK.get();stack.pop();closed=true;if (stack.isEmpty()) STACK.remove();
    }
}
