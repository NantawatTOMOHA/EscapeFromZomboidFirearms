package pz.escapefromzomboidfirearms.zb;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;

import zombie.ZomboidFileSystem;

/**
 * QA only (wave 1488, "the chamber of a loaded weapon is still empty"): what one drawn model adds to a frame. Three
 * screenshots of the same moment of an action (this profile's Screenshots folder): two with the model (a1, a2) and
 * one without it (b). Its pixels are those both "with" frames agree on (within the threshold, the largest channel
 * step of 0..255) and the "without" frame differs from in both; a1 against a2 is the noise of two runs. A crop of
 * a1 | b | the pixels around them is written next to them for review. No game state is read or changed.
 */
public final class QaImageDiff {
    private QaImageDiff() {
    }

    /** "pending" while a file is missing or still being written; else "round= control= raw= box=x0,y0,x1,y1 size=WxH crop=". */
    public static String diff(String a1, String a2, String b, int threshold, String crop) {
        return diff(a1, a2, b, threshold, crop, 0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /**
     * The same, counted only inside the screen box x0..x1, y0..y1 (pixels, inclusive; chamberBox): the scene around the
     * weapon (clouds, a run's sway, the console) changes between runs by far more than one round covers.
     */
    public static String diff(String a1, String a2, String b, int threshold, String crop, int bx0, int by0, int bx1, int by1) {
        try {
            Path dir = Path.of(ZomboidFileSystem.instance.getScreenshotDir());
            BufferedImage x = read(dir.resolve(a1)), y = read(dir.resolve(a2)), z = read(dir.resolve(b));
            if (x == null || y == null || z == null) return "pending";
            int w = x.getWidth(), h = x.getHeight();
            if (y.getWidth() != w || z.getWidth() != w || y.getHeight() != h || z.getHeight() != h) return "error=size";
            long round = 0, control = 0, raw = 0;
            int x0 = w, y0 = h, x1 = -1, y1 = -1;
            boolean[] mask = new boolean[w * h];
            int ia = Math.max(0, bx0), ja = Math.max(0, by0), ib = Math.min(w - 1, bx1), jb = Math.min(h - 1, by1);
            for (int j = ja; j <= jb; j++) {
                for (int i = ia; i <= ib; i++) {
                    int p = x.getRGB(i, j), q = y.getRGB(i, j), r = z.getRGB(i, j);
                    boolean noise = step(p, q) > threshold, off = step(p, r) > threshold;
                    if (noise) control++;
                    if (off) raw++;
                    if (off && !noise && step(q, r) > threshold) {
                        round++;
                        mask[j * w + i] = true;
                        x0 = Math.min(x0, i); y0 = Math.min(y0, j); x1 = Math.max(x1, i); y1 = Math.max(y1, j);
                    }
                }
            }
            String written = "-";
            if (round > 0 && crop != null && !crop.isEmpty()) {
                int m = 48, cx0 = Math.max(0, x0 - m), cy0 = Math.max(0, y0 - m);
                int cw = Math.min(w, x1 + m + 1) - cx0, ch = Math.min(h, y1 + m + 1) - cy0;
                BufferedImage out = new BufferedImage(cw * 3, ch, BufferedImage.TYPE_INT_RGB);
                for (int j = 0; j < ch; j++) {
                    for (int i = 0; i < cw; i++) {
                        out.setRGB(i, j, x.getRGB(cx0 + i, cy0 + j));
                        out.setRGB(cw + i, j, z.getRGB(cx0 + i, cy0 + j));
                        out.setRGB(2 * cw + i, j, mask[(cy0 + j) * w + cx0 + i] ? 0xff3030 : (x.getRGB(cx0 + i, cy0 + j) >> 2) & 0x3f3f3f);
                    }
                }
                Path file = dir.resolve(crop);
                ImageIO.write(out, "png", file.toFile());
                written = file.getFileName().toString();
            }
            return String.format(Locale.ROOT, "round=%d control=%d raw=%d box=%d,%d,%d,%d counted=%d,%d,%d,%d size=%dx%d crop=%s",
                    round, control, raw, x1 < 0 ? -1 : x0, x1 < 0 ? -1 : y0, x1, y1, ia, ja, ib, jb, w, h, written);
        } catch (Exception | LinkageError e) {
            return "error=" + e;
        }
    }

    /**
     * The screen box of a character's chambered round now (pixels of this window): the round's base (where the last
     * chamber check carried it, else its chamber node) and 4.5 cm along the bore, a case's width round it, through the
     * first-person pose's eye (FpPose eyePose: the weapon's frame in the eye's, +X left, +Y up, -Z ahead) and Viewpoint's
     * frame field of view, grown by margin pixels; "none" without a pose, a round or a field of view.
     */
    public static String chamberBox(zombie.characters.IsoGameCharacter c, double margin) {
        FpPose.Request r = c == null ? null : FpPose.requests.get(c);
        double[] q = r == null ? null : r.eyePose;
        double fov = ViewpointBridge.floatOf("viewpoint.core.View.frameFovY", 0f);
        if (q == null || fov <= 0) return "none";
        double[] base = r.weapon != null && r.weapon.equals(MovingWeaponParts.chamberKey) ? MovingWeaponParts.chamberAt : null;
        if (base == null) {
            MovingWeaponPartsData data = MovingWeaponPartsData.get();
            MovingWeaponPartsData.Weapon w = data == null || r.weapon == null ? null : data.weapons.get(r.weapon);
            MovingWeaponPartsData.Node n = w == null ? null : w.nodes().get(MovingWeaponParts.PATRON);
            if (n == null) return "none";
            base = new double[] {n.bind()[3], n.bind()[7], n.bind()[11]};
        }
        int width = zombie.core.Core.getInstance().getScreenWidth(), height = zombie.core.Core.getInstance().getScreenHeight();
        double tan = Math.tan(fov / 2), aspect = (double) width / Math.max(1, height), side = .006, length = .045 * GripIK.U;
        double minX = Double.POSITIVE_INFINITY, minY = minX, maxX = -minX, maxY = maxX;
        for (int k = 0; k < 8; k++) {
            double[] e = GripIK.point(q, base[0] + ((k & 1) == 0 ? -side : side), base[1] + ((k & 2) == 0 ? 0 : length),
                    base[2] + ((k & 4) == 0 ? -side : side));
            if (e[2] > -1e-4) return "none";
            double x = (1 - e[0] / (-e[2] * tan * aspect)) / 2 * width, y = (1 - e[1] / (-e[2] * tan)) / 2 * height;
            minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }
        return String.format(Locale.ROOT, "%d,%d,%d,%d", (int) Math.floor(minX - margin), (int) Math.floor(minY - margin),
                (int) Math.ceil(maxX + margin), (int) Math.ceil(maxY + margin));
    }

    private static BufferedImage read(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) < 64) return null;
            return ImageIO.read(file.toFile());
        } catch (Exception e) {
            return null;
        }
    }

    private static int step(int p, int q) {
        int r = Math.abs(((p >> 16) & 255) - ((q >> 16) & 255));
        int g = Math.abs(((p >> 8) & 255) - ((q >> 8) & 255));
        int b = Math.abs((p & 255) - (q & 255));
        return Math.max(r, Math.max(g, b));
    }
}
