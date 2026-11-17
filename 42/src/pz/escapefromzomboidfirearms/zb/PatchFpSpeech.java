package pz.escapefromzomboidfirearms.zb;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.chat.ChatElement;
import zombie.ui.UIFont;

/** Preserve speech creation, logging and networking; redirect only its local draw. */
public final class PatchFpSpeech {
    @Patch(className="zombie.chat.ChatElement", methodName="addChatLine", strictMatch=true)
    public static class Added {
        @Patch.OnExit
        public static void exit(@Patch.This ChatElement element, @Patch.Argument(0) String message,
                @Patch.Argument(1) float r, @Patch.Argument(2) float g, @Patch.Argument(3) float b,
                @Patch.Argument(4) UIFont font, @Patch.Argument(5) float range, @Patch.Argument(6) String tag,
                @Patch.Argument(7) boolean a, @Patch.Argument(8) boolean c, @Patch.Argument(9) boolean d,
                @Patch.Argument(10) boolean e, @Patch.Argument(11) boolean f, @Patch.Argument(12) boolean h) {
            FpSpeechNotice.added(element,message);
        }
    }

    @Patch(className="zombie.chat.ChatElement", methodName="renderBatched", strictMatch=true)
    public static class Draw {
        @Patch.OnEnter(skipOn=true)
        public static boolean enter(@Patch.This ChatElement element, @Patch.Argument(0) int viewer,
                @Patch.Argument(1) int x, @Patch.Argument(2) int y, @Patch.Argument(3) boolean ignoreRadio) {
            return FpSpeechNotice.suppress(element,viewer,ignoreRadio);
        }
    }
}
