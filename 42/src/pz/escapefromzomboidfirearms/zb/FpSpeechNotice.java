package pz.escapefromzomboidfirearms.zb;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import me.zed_0xff.zombie_buddy.Exposer;
import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.integration.LuaReturn;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import se.krka.kahlua.vm.LuaClosure;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.chat.ChatElement;
import zombie.ui.UIFont;

/** Main-thread, optional local presentation. No chat data or Viewpoint state is changed. */
@Exposer.LuaClass(name="TWFPSpeechBridge")
public final class FpSpeechNotice {
    private static boolean checked,disabled,seenAdd,seenDraw;
    private static ChatElement lastElement;
    private static String lastText;
    private static Object lastSink;
    private static ChatElement pendingElement;
    private static final ArrayDeque<String> pending=new ArrayDeque<>();
    private static long routed,suppressed;

    private static void method(Class<?> owner, String name, Class<?> result, boolean isStatic, Class<?>... args)
            throws ReflectiveOperationException {
        Method m=owner.getMethod(name,args);
        if (!Modifier.isPublic(owner.getModifiers()) || !Modifier.isPublic(m.getModifiers())
                || Modifier.isStatic(m.getModifiers())!=isStatic || m.getReturnType()!=result)
            throw new NoSuchMethodException(owner.getName()+"."+name);
    }

    private static void field(Class<?> owner, String name, Class<?> type) throws ReflectiveOperationException {
        Field f=owner.getField(name);
        if (!Modifier.isPublic(owner.getModifiers()) || !Modifier.isPublic(f.getModifiers())
                || !Modifier.isStatic(f.getModifiers()) || f.getType()!=type)
            throw new NoSuchFieldException(owner.getName()+"."+name);
    }

    private static boolean guard() {
        if (disabled) return false;
        if (checked) return true;
        try {
            method(ChatElement.class,"addChatLine",void.class,false,String.class,float.class,float.class,float.class,
                    UIFont.class,float.class,String.class,boolean.class,boolean.class,boolean.class,
                    boolean.class,boolean.class,boolean.class);
            method(ChatElement.class,"renderBatched",void.class,false,int.class,int.class,int.class,boolean.class);
            method(ChatElement.class,"getSayLine",String.class,false);
            method(ChatElement.class,"getSayLineTag",String.class,false);
            method(ChatElement.class,"getHasChatToDisplay",boolean.class,false);
            field(IsoPlayer.class,"players",IsoPlayer[].class);
            method(IsoPlayer.class,"getChatElement",ChatElement.class,false);
            method(IsoPlayer.class,"isLocalPlayer",boolean.class,false);
            method(IsoPlayer.class,"getPlayerNum",int.class,false);
            method(ViewpointBridge.class,"state",int.class,true);
            field(LuaManager.class,"env",KahluaTable.class);
            field(LuaManager.class,"thread",KahluaThread.class);
            field(LuaManager.class,"caller",LuaCaller.class);
            method(KahluaTable.class,"rawget",Object.class,false,Object.class);
            method(LuaCaller.class,"protectedCall",LuaReturn.class,false,KahluaThread.class,Object.class,Object[].class);
            method(LuaReturn.class,"isSuccess",boolean.class,false);
            method(LuaReturn.class,"getFirst",Object.class,false);
            if (!Modifier.isPublic(LuaClosure.class.getModifiers())) throw new IllegalAccessException("LuaClosure");
            checked=true;
            return true;
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    private static void fail(Throwable t) {
        if (!disabled) System.out.println("[TW-FP-SPEECH] disabled: "+t);
        disabled=true;
        reset();
    }

    /** Both exact advice targets must have executed before any screen notice is admitted. */
    public static boolean ready() {
        return guard() && seenAdd && seenDraw;
    }

    public static void reset() {
        lastElement=null;lastText=null;lastSink=null;
        pendingElement=null;pending.clear();
    }

    public static String info() {
        return "ready="+ready()+" disabled="+disabled+" add="+seenAdd+" draw="+seenDraw
                +" routed="+routed+" suppressed="+suppressed;
    }

    private static IsoPlayer player(ChatElement element) {
        if (!guard() || element==null || ViewpointBridge.state()!=1) return null;
        IsoPlayer[] players=IsoPlayer.players;
        IsoPlayer p=players!=null && players.length>0 ? players[0] : null;
        return p!=null && p.isLocalPlayer() && p.getPlayerNum()==0 && p.getChatElement()==element ? p : null;
    }

    private static boolean send(IsoPlayer player, String message) {
        if (LuaManager.env==null || LuaManager.thread==null || LuaManager.caller==null) return false;
        Object sink=LuaManager.env.rawget("TWFPSpeechRoute");
        if (!(sink instanceof LuaClosure)) return false;
        if (sink!=lastSink) { lastElement=null;lastText=null;lastSink=sink; }
        LuaReturn result=LuaManager.caller.protectedCall(LuaManager.thread,sink,new Object[]{player,message});
        if (!result.isSuccess()) {
            fail(new IllegalStateException("TWFPSpeechRoute failed"));
            return false;
        }
        if (!Boolean.TRUE.equals(result.getFirst())) return false;
        lastSink=sink;
        return true;
    }

    /** OnExit of line creation; the parent logging, audio and MP paths remain intact. */
    public static void added(ChatElement element, String message) {
        try {
            seenAdd=true;
            IsoPlayer p=player(element);
            if (p==null || message==null || !element.getHasChatToDisplay() || !message.equals(element.getSayLine())) return;
            // Radio is routed at draw time, after the native ignoreRadio decision.
            if ("radio".equals(element.getSayLineTag())) return;
            if (!ready()) {
                if (pendingElement!=element) { pending.clear();pendingElement=element; }
                if (pending.size()>=64) { fail(new IllegalStateException("draw hook not observed"));return; }
                pending.addLast(message);
                return;
            }
            if (send(p,message)) {
                lastElement=element;lastText=message;routed++;
            }
        } catch (Throwable t) { fail(t); }
    }

    /** Hide only viewer 0's own element, and only after the Lua queue accepted it. */
    public static boolean suppress(ChatElement element, int viewer, boolean ignoreRadio) {
        try {
            seenDraw=true;
            IsoPlayer p=player(element);
            if (viewer!=0 || p==null || !ready()) return false;
            if (!element.getHasChatToDisplay()) { if (element==lastElement) reset();return false; }
            // Keep radio visibility native when VP says those lines are unheard.
            if (ignoreRadio && "radio".equals(element.getSayLineTag())) return false;
            if (!send(p,null)) { reset();return false; }
            if (pendingElement==element) {
                while (!pending.isEmpty()) {
                    String next=pending.removeFirst();
                    if (!send(p,next)) { reset();return false; }
                    lastElement=element;lastText=next;routed++;
                }
                pendingElement=null;
            }
            String text=element.getSayLine();
            if (text==null || text.isEmpty()) return false;
            if (element!=lastElement || !text.equals(lastText)) {
                if (!send(p,text)) return false;
                lastElement=element;lastText=text;routed++;
            }
            suppressed++;
            return true;
        } catch (Throwable t) { fail(t);return false; }
    }
}
