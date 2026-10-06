package baritone.process;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

/**
 * Watches the opponent and writes what it does as short event lines, so a fight can be read as "how did they move,
 * when did they swing, what did they hold" without scanning the per-tick rows. One line per change, never per tick:
 * <pre>
 * @t sw d=2.41 gap=11 yE=4 pE=2 crit spr it=iron_sword mv=A   they swung (gap = ticks since their last swing,
 *                                                              yE/pE = aim error at us in degrees)
 * @t hit<-sw                                                    we took damage within 4 ticks of that swing
 * @t use+ shield d=3.1  /  @t use- shield n=11                  started / stopped using an item, and for how long
 * @t item iron_sword>golden_apple                               hotbar change
 * @t mv A>S n=14 d=2.9                                          movement class changed after n ticks
 * @t jmp d=2.2   /  @t spr+ d=4.0   /  @t spr- d=2.5            jump, sprint start and stop
 * #opp swings=.. hit=.. gap=mean/sd ...                         one summary line at the end
 * </pre>
 * Movement classes are relative to the line between the two: A approach, R retreat, L and Q strafe either way,
 * Z still. A class must hold two ticks to count, so one noisy tick does not write a line.
 */
final class PvpOpponent {
    private final Consumer<String> sink;

    private boolean swinging, using, sprinting, ground = true;
    private String item = "", useItem = "";
    private int useStart, lastSwing = -100, swingAt = -100;
    private char mv = 'Z', pendingMv = 'Z';
    private int mvStart, pendingTicks;
    private boolean swingResolved = true;

    private int swings, swingsHit, crits, sprintSwings, jumps, gaps, uses, inReachTicks, ticks;
    private double gapSum, gapSq, distSum, yawSum, pitSum;
    private final int[] mvTicks = new int[5];
    private static final String MV = "ARLQZ";

    PvpOpponent(Consumer<String> sink) {
        this.sink = sink;
    }

    void observe(int tick, Player me, LivingEntity tg, boolean tookDamage) {
        ticks++;
        Vec3 to = me.position().subtract(tg.position());
        double dist = Math.sqrt(to.x * to.x + to.z * to.z);
        String d = " d=" + f(tg.distanceTo(me));
        String now = tg.getMainHandItem().getItem().toString().replace("minecraft:", "");
        if (!now.equals(item)) {
            if (!item.isEmpty()) emit(tick, "item " + item + ">" + now);
            item = now;
        }
        movement(tick, tg, to, dist, d);
        if (tg.distanceTo(me) <= 3.2) inReachTicks++;

        boolean spr = tg.isSprinting();
        if (spr != sprinting) emit(tick, spr ? "spr+" + d : "spr-" + d);
        sprinting = spr;

        boolean air = !tg.onGround();
        if (ground && air && tg.getDeltaMovement().y > 0.3) {
            jumps++;
            emit(tick, "jmp" + d);
        }
        ground = !air;

        boolean use = tg.isUsingItem() || tg.isBlocking();
        if (use && !using) {
            useItem = tg.isBlocking() ? "shield" : tg.getUseItem().getItem().toString().replace("minecraft:", "");
            useStart = tick;
            uses++;
            emit(tick, "use+ " + useItem + d);
        } else if (!use && using) {
            emit(tick, "use- " + useItem + " n=" + (tick - useStart));
        }
        using = use;

        boolean sw = tg.swinging;
        if (sw && !swinging) {
            swings++;
            int gap = tick - lastSwing;
            StringBuilder sb = new StringBuilder("sw").append(d);
            if (lastSwing > 0 && gap < 60) {
                gaps++;
                gapSum += gap;
                gapSq += (double) gap * gap;
                sb.append(" gap=").append(gap);
            }
            float yE = Mth.wrapDegrees(tg.getYRot() - (float) (Math.toDegrees(Math.atan2(-to.x, to.z))));
            double eyeDy = me.getEyeY() - tg.getEyeY();
            float pE = Mth.wrapDegrees(tg.getXRot() - (float) -Math.toDegrees(Math.atan2(eyeDy, dist)));
            yawSum += Math.abs(yE);
            pitSum += Math.abs(pE);
            distSum += tg.distanceTo(me);
            sb.append(" yE=").append(Math.round(yE)).append(" pE=").append(Math.round(pE));
            if (!tg.onGround() && tg.getDeltaMovement().y < 0 && tg.fallDistance > 0) {
                crits++;
                sb.append(" crit");
            }
            if (spr) {
                sprintSwings++;
                sb.append(" spr");
            }
            sb.append(" it=").append(now).append(" mv=").append(mv);
            emit(tick, sb.toString());
            lastSwing = tick;
            swingAt = tick;
            swingResolved = false;
        }
        swinging = sw;
        if (!swingResolved && tookDamage && tick - swingAt <= 4) {
            swingsHit++;
            swingResolved = true;
            emit(tick, "hit<-sw");
        }
    }

    private void movement(int tick, LivingEntity tg, Vec3 to, double dist, String d) {
        Vec3 v = tg.getDeltaMovement();
        double speed = Math.sqrt(v.x * v.x + v.z * v.z);
        char c = 'Z';
        if (speed >= 0.03 && dist > 1.0e-3) {
            double radial = (v.x * to.x + v.z * to.z) / dist;
            double lateral = (v.x * -to.z + v.z * to.x) / dist;
            c = Math.abs(radial) >= Math.abs(lateral) ? (radial > 0 ? 'A' : 'R') : (lateral > 0 ? 'L' : 'Q');
        }
        if (c == mv) {
            pendingTicks = 0;
        } else if (c == pendingMv) {
            if (++pendingTicks >= 2) {
                emit(tick, "mv " + mv + ">" + c + " n=" + (tick - mvStart) + d);
                mv = c;
                mvStart = tick;
                pendingTicks = 0;
            }
        } else {
            pendingMv = c;
            pendingTicks = 1;
        }
        mvTicks[MV.indexOf(mv)]++;
    }

    /** The one-line summary written when the fight ends. */
    String summary() {
        double gapMean = gaps > 0 ? gapSum / gaps : 0;
        double gapSd = gaps > 1 ? Math.sqrt(Math.max(0, gapSq / gaps - gapMean * gapMean)) : 0;
        StringBuilder mvs = new StringBuilder();
        for (int i = 0; i < MV.length(); i++) mvs.append(MV.charAt(i)).append(ticks > 0 ? Math.round(100.0 * mvTicks[i] / ticks) : 0).append(i < 4 ? "/" : "");
        return "#opp swings=" + swings + " hit=" + swingsHit + " gap=" + f(gapMean) + "/" + f(gapSd)
                + " crit=" + crits + " spr=" + sprintSwings + " jumps=" + jumps + " uses=" + uses
                + " d@sw=" + f(swings > 0 ? distSum / swings : 0) + " yE=" + f(swings > 0 ? yawSum / swings : 0) + " pE=" + f(swings > 0 ? pitSum / swings : 0)
                + " inReach=" + (ticks > 0 ? Math.round(100.0 * inReachTicks / ticks) : 0) + "% mv%=" + mvs;
    }

    private void emit(int tick, String s) {
        sink.accept("@" + tick + " " + s);
    }

    private static String f(double v) {
        return String.format("%.2f", v);
    }
}
