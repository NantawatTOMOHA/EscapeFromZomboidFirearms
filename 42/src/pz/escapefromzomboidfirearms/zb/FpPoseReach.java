package pz.escapefromzomboidfirearms.zb;

/** Head-preserving upper-body reach, using the PZ spine and the existing clavicle limit.
 * No weapon/class/sex table: the lengths, pivots and remaining twist budget are the live body's.
 * Search steps below are numerical resolution, not authored body poses.
 */
final class FpPoseReach {
    static final double EPS = 1e-7, STEP = Math.toRadians(5);

    private FpPoseReach() { }

    /** PZ exposes these angles in radians. Already used body/shoulder twist consumes the budget. */
    static double budget(double max, double twist, double shoulder) {
        return !Double.isFinite(max + twist + shoulder) || max < 0 || max > Math.PI ? 0
                : Math.max(0, max - Math.max(Math.abs(twist), Math.abs(shoulder)));
    }

    /** Rotate Spine1 and its upper-body descendants about Spine1--Head. The head is a
     * counter-rotation driver: its entire model frame stays unchanged. All spine, neck,
     * clavicle and arm link lengths therefore stay unchanged, including Neck--Head.
     */
    static double[][] body(double[][] model, FpPose.Skeleton s, double angle) {
        if (Math.abs(angle) < 1e-12) return model;
        double[] pivot = GripIK.pos(model[s.chain[2]]);
        double[] axis = GripIK.sub(GripIK.pos(model[s.head]), pivot);
        if (GripIK.norm(axis) < 1e-7) return model;
        double[] turn = GripIK.rigid(GripIK.normalize(axis), angle, pivot);
        double[][] out = model.clone();
        for (int b : new int[] {s.chain[2], s.chain[3], s.lc, s.ua, s.fa, s.lh, s.rc, s.rua, s.rfa, s.rh}) {
            out[b] = GripIK.mulNew(turn, model[b]);
        }
        return out;
    }

    /** The look's rotation above level in model space (eye: +X left, +Y up, -Z ahead): the axis
     * that turns the level forward to the eye's forward, and the angle (0 at or below level). */
    static double[] lookUp(double[] eye) {
        if (eye == null) return null;
        double[] forward = GripIK.normalize(new double[] {-eye[2], -eye[6], -eye[10]});
        double[] level = {forward[0], 0, forward[2]};
        double flat = GripIK.norm(level);
        if (flat < 1e-6 || !(forward[1] > 0)) return null;
        double[] axis = GripIK.cross(GripIK.scale(level, 1 / flat), forward);
        if (GripIK.norm(axis) < 1e-9) return null;
        axis = GripIK.normalize(axis);
        return new double[] {axis[0], axis[1], axis[2], Math.atan2(forward[1], flat)};
    }

    /** Spine1 and its upper-body descendants turned about the eye by angle along the look's
     * upward rotation (FB1: looking up, the PZ torso does not pitch, the weapon at the eye rises
     * out of the support arm's reach and the twist budget alone tore the shoulders; Tarkov's
     * first-person arms follow the camera). The head stays (counter-rotation driver), as in body(). */
    static double[][] pitch(double[][] model, FpPose.Skeleton s, double[] up, double[] eye, double angle) {
        if (up == null || eye == null || Math.abs(angle) < 1e-12) return model;
        double[] turn = GripIK.rigid(new double[] {up[0], up[1], up[2]}, angle, new double[] {eye[3], eye[7], eye[11]});
        double[][] out = model.clone();
        for (int b : new int[] {s.chain[2], s.chain[3], s.lc, s.ua, s.fa, s.lh, s.rc, s.rua, s.rfa, s.rh}) {
            out[b] = GripIK.mulNew(turn, model[b]);
        }
        return out;
    }

    /** The smallest share of the look's upward angle at which both hands reach their contacts
     * (score as the twist search); unreachable even at the full angle: the best sampled share. */
    static double lift(double[][] model, FpPose.Skeleton s, double[] left, double[] right, double[] P, double back, double[] eye) {
        double[] up = lookUp(eye);
        if (up == null) return 0;
        double full = up[3];
        double initial = score(model, s, left, right, P, back, eye);
        if (initial <= EPS) return 0;
        double best = 0, minimum = initial;
        for (int i = 1; i <= 16; i++) {
            double angle = full * i / 16, cost = score(pitch(model, s, up, eye, angle), s, left, right, P, back, eye);
            if (cost < minimum - 1e-10) { best = angle; minimum = cost; }
            if (cost <= EPS) break;
        }
        if (minimum <= EPS && best > 0) {
            double lo = Math.max(0, best - full / 16), hi = best;
            for (int i = 0; i < 18; i++) {
                double mid = (lo + hi) / 2;
                if (score(pitch(model, s, up, eye, mid), s, left, right, P, back, eye) <= EPS) hi = mid; else lo = mid;
            }
            best = hi;
        }
        return best;
    }

    /** Same minimum swing as the arm solver, analytically (avoids a bisection per search sample). */
    static double swing(double[] clavicle, double[] upper, double[] fore, double[] hand, double[] goal) {
        double[] v = GripIK.sub(GripIK.pos(upper), GripIK.pos(clavicle));
        double[] g = GripIK.sub(goal, GripIK.pos(clavicle));
        double c = GripIK.norm(v), d = GripIK.norm(g);
        double reach = GripIK.dist(GripIK.pos(upper), GripIK.pos(fore)) + GripIK.dist(GripIK.pos(fore), GripIK.pos(hand)) - GripIK.REACH_MARGIN;
        if (c < 1e-9 || d < 1e-9 || GripIK.dist(GripIK.pos(upper), goal) <= reach) return 0;
        double alpha = Math.acos(Math.max(-1, Math.min(1, GripIK.dot(v, g) / (c * d))));
        double beta = Math.acos(Math.max(-1, Math.min(1, (c * c + d * d - reach * reach) / (2 * c * d))));
        return Math.min(GripIK.CLAVICLE_MAX_AIM, Math.max(0, alpha - beta));
    }

    /** Necessary no-stretch distance and own-side elbow-circle clearance, for either arm. */
    static double miss(double[][] model, FpPose.Skeleton s, double[] goal, double[] eye, int side) {
        int cl = side == 0 ? s.lc : s.rc, ua = side == 0 ? s.ua : s.rua;
        int fa = side == 0 ? s.fa : s.rfa, hand = side == 0 ? s.lh : s.rh;
        double[] LC = model[cl], UA = model[ua], FA = model[fa], H = model[hand];
        double[] sh = GripIK.pos(UA), target = GripIK.pos(goal);
        double theta = swing(LC, UA, FA, H, target);
        double[] axis = GripIK.cross(GripIK.sub(sh, GripIK.pos(LC)), GripIK.sub(target, GripIK.pos(LC)));
        if (theta > 0 && GripIK.norm(axis) > 1e-9) {
            sh = GripIK.point(GripIK.rigid(GripIK.normalize(axis), theta, GripIK.pos(LC)), sh[0], sh[1], sh[2]);
        }
        double l1 = GripIK.dist(GripIK.pos(UA), GripIK.pos(FA)), l2 = GripIK.dist(GripIK.pos(FA), GripIK.pos(H));
        double distance = GripIK.dist(sh, target), min = Math.abs(l1 - l2) + EPS, max = l1 + l2 - EPS;
        double miss = Math.max(0, Math.max(min - distance, distance - max));
        if (eye != null && distance > EPS) {
            double[] a = GripIK.scale(GripIK.sub(target, sh), 1 / distance);
            double reach = Math.max(min, Math.min(distance, max)), x = (l1 * l1 - l2 * l2 + reach * reach) / (2 * reach);
            double h = Math.sqrt(Math.max(0, l1 * l1 - x * x));
            double sign = side == 0 ? 1 : -1;
            double[] own = {sign * eye[0], sign * eye[4], sign * eye[8]};
            double dot = GripIK.dot(own, a), radius = h * Math.sqrt(Math.max(0, 1 - dot * dot));
            double maximum = GripIK.dot(GripIK.sub(GripIK.add(sh, GripIK.scale(a, x)), GripIK.pos(eye)), own) + radius;
            miss = Math.max(miss, radius * (1 - Math.cos(GripIK.CLEAR_STEP)) - maximum);
        }
        return miss;
    }

    static double[] backTarget(double[] left, double[] P, double back) {
        double[] out = left.clone();
        double[] bore = GripIK.normalize(new double[] {P[1], P[5], P[9]});
        GripIK.setPos(out, GripIK.sub(GripIK.pos(left), GripIK.scale(bore, back)));
        return out;
    }

    static double score(double[][] model, FpPose.Skeleton s, double[] left, double[] right, double[] P, double back, double[] eye) {
        double l = miss(model, s, left, eye, 0);
        if (back > 0 && l > EPS) l = Math.min(l, miss(model, s, backTarget(left, P, back), eye, 0));
        return Math.max(l, miss(model, s, right, eye, 1));
    }

    /** Smallest feasible sampled twist; if the head-preserving envelope cannot reach,
     * minimize the measured residual and leave it visible. No claim of full-body IK.
     */
    static double turn(double[][] model, FpPose.Skeleton s, double[] left, double[] right, double[] P, double back, double[] eye, double cap) {
        double initial = score(model, s, left, right, P, back, eye);
        if (initial <= EPS || cap <= 0) return 0;
        double best = 0, minimum = initial;
        for (int sign : new int[] {1, -1}) {
            for (double a = STEP; a < cap + STEP; a += STEP) {
                double angle = sign * Math.min(cap, a);
                double cost = score(body(model, s, angle), s, left, right, P, back, eye);
                if (cost < minimum - 1e-10 || cost <= EPS && Math.abs(angle) < Math.abs(best)) {
                    best = angle; minimum = cost;
                }
                if (cost <= EPS) break;
            }
        }
        if (best == 0) return 0;
        if (minimum > EPS) {
            double lo = Math.max(-cap, best - STEP), hi = Math.min(cap, best + STEP);
            for (int i = 0; i < 16; i++) {
                double a = (2 * lo + hi) / 3, b = (lo + 2 * hi) / 3;
                if (score(body(model, s, a), s, left, right, P, back, eye) < score(body(model, s, b), s, left, right, P, back, eye)) hi = b; else lo = a;
            }
            double mid = (lo + hi) / 2, cost = score(body(model, s, mid), s, left, right, P, back, eye);
            if (cost < minimum) { best = mid; minimum = cost; }
        }
        if (minimum <= EPS) {
            double lo = 0, hi = Math.abs(best), sign = Math.signum(best);
            for (int i = 0; i < 18; i++) {
                double mid = (lo + hi) / 2;
                if (score(body(model, s, sign * mid), s, left, right, P, back, eye) <= EPS) hi = mid; else lo = mid;
            }
            best = sign * hi;
        }
        return best;
    }

    /** Only an authored high-ready/retreat may bring the entire weapon towards the body.
     * The direction is measured shoulder--support, and the cap is the existing SLIDE_MAX.
     * ADS and source patrol get a zero cap. Both contacts and the bend goals follow it.
     */
    static double[] pull(double[][] model, FpPose.Skeleton s, double[] left, double[] right, double[] P, double back, double[] eye, double cap) {
        double initial = score(model, s, left, right, P, back, eye);
        if (initial <= EPS || cap <= 0) return new double[3];
        double[] axis = GripIK.sub(GripIK.pos(model[s.ua]), GripIK.pos(left));
        if (GripIK.norm(axis) < EPS) return new double[3];
        axis = GripIK.normalize(axis);
        double best = 0, cost = initial;
        for (int i = 1; i <= 16; i++) {
            double distance = cap * i / 16, next = pullScore(model, s, left, right, P, back, eye, axis, distance);
            if (next < cost) { cost = next; best = distance; }
            if (next <= EPS) break;
        }
        if (cost <= EPS) {
            double lo = Math.max(0, best - cap / 16), hi = best;
            for (int i = 0; i < 18; i++) {
                double mid = (lo + hi) / 2;
                if (pullScore(model, s, left, right, P, back, eye, axis, mid) <= EPS) hi = mid; else lo = mid;
            }
            best = hi;
        }
        return GripIK.scale(axis, best);
    }

    private static double pullScore(double[][] model, FpPose.Skeleton s, double[] left, double[] right, double[] P, double back, double[] eye, double[] axis, double distance) {
        double[] l = left.clone(), r = right.clone(), delta = GripIK.scale(axis, distance);
        GripIK.setPos(l, GripIK.add(GripIK.pos(l), delta)); GripIK.setPos(r, GripIK.add(GripIK.pos(r), delta));
        return score(model, s, l, r, P, back, eye);
    }
}
