package baritone.pathing.kinematic;

import baritone.pathing.kinematic.TasBrute.BoxWorld;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TasBruteTest {

    private static PlayerSim standing(PlayerSim.World w, double x, double y, double z) {
        PlayerSim s = new PlayerSim(w);
        s.x = x;
        s.y = y;
        s.z = z;
        s.onGround = true;
        s.vy = -0.0784000015258789;
        return s;
    }

    private static TasBrute.Result run(String name, PlayerSim.World w, PlayerSim start, float yaw, TasBrute.Landing goal, float maxTurn) {
        TasBrute b = new TasBrute(w);
        b.maxTurn = maxTurn;
        if (name.endsWith("-long")) {
            b.beam = 20000;
            b.maxTicks = 70;
        }
        long t0 = System.nanoTime();
        TasBrute.Result r = b.search(start, yaw, goal);
        System.out.printf("TAS %s turn=%.0f %s (%d ms)%n", name, maxTurn, r == null ? "NONE" : r.ticks() + "t " + r, (System.nanoTime() - t0) / 1_000_000);
        return r;
    }

    @Test
    public void flatGap3() {
        BoxWorld w = new BoxWorld().fill(0, -1, -3, 0, -1, 0, BoxWorld.FULL).put(0, -1, 4, BoxWorld.FULL);
        assertNotNull(run("gap3", w, standing(w, 0.5, 0, -2.5), 0, new TasBrute.Landing(0, 0, 4), 30));
    }

    /**
     * Practice parkour ladder spiral: 1 tall ladders round a pillar, each one up and on the next face. Hanging on the
     * north face's ladder at a given height in its cell, round the corner onto the east face's ladder one up.
     */
    @Test
    public void ladderCornerLeap() {
        double plate = 3 / 16.0;
        BoxWorld w = new BoxWorld().fill(0, -2, 0, 0, 4, 0, BoxWorld.FULL)
                .ladder(0, 0, -1, new double[][]{{0, 0, 1 - plate, 1, 1, 1}})
                .ladder(1, 1, 0, new double[][]{{0, 0, 0, plate, 1, 1}});
        for (double rise : new double[]{0, 0.5, 0.75, 0.9}) {
            PlayerSim s = new PlayerSim(w);
            s.x = 0.5;
            s.y = rise;
            s.z = -plate - PlayerSim.HALF_WIDTH - 0.0125;
            s.vy = 0;
            TasBrute b = new TasBrute(w);
            b.maxTicks = 30;
            b.floorY = -1;
            long t0 = System.nanoTime();
            TasBrute.Result r = b.search(s, 0, new TasBrute.Landing(1, 1, 0).grab());
            System.out.printf("TAS ladder-corner rise=%.2f %s (%d ms)%n", rise, r == null ? "NONE" : r.ticks() + "t " + r, (System.nanoTime() - t0) / 1_000_000);
        }
    }

    /** Take-off, a 3 long 2 high wall and the landing in one line: around the wall's side. */
    @Test
    public void alignedNeo3() {
        BoxWorld w = new BoxWorld().fill(0, -1, -3, 0, -1, 0, BoxWorld.FULL).fill(0, 0, 1, 0, 1, 3, BoxWorld.FULL).put(0, -1, 4, BoxWorld.FULL);
        for (float turn : new float[]{30, 90})
            run("neo3-aligned", w, standing(w, 0.5, 0, -2.5), 0, new TasBrute.Landing(0, 0, 4), turn);
        // a 10 block run-up, beam 20000, 70 ticks and free camera (180/tick) found none either (21 s): the box can't
        // clear the wall's side before z 0.7 and must still be beside it at z 4.3, 3.6 blocks flat, past a momentum 4 gap
    }

    /**
     * The same wall, entered at the speed a previous sprint jump still has on landing. Backwards is allowed so the
     * search can brake, and the camera turns at the bot's 32 degrees a tick.
     */
    @Test
    public void alignedNeo3FromThePreviousJump() {
        BoxWorld w = new BoxWorld().fill(0, -1, -3, 0, -1, 0, BoxWorld.FULL).fill(0, 0, 1, 0, 1, 3, BoxWorld.FULL).put(0, -1, 4, BoxWorld.FULL);
        // z=0.3 is already at the lip. z=-0.7 and z=-1.7 leave one and two blocks to curve before the wall.
        for (double z0 : new double[]{0.3, -0.7, -1.7}) {
            for (double vz : new double[]{0.25, 0.30, 0.33, 0.40}) {
                PlayerSim s = standing(w, 0.5, 0, z0);
                s.sprinting = true;
                s.vz = vz;
                TasBrute b = new TasBrute(w);
                b.allowBack = true;
                b.maxTurn = 32;
                b.beam = 8000;
                b.maxTicks = 36;
                long t0 = System.nanoTime();
                TasBrute.Result r = b.search(s, 0, new TasBrute.Landing(0, 0, 4));
                System.out.printf("TAS neo3-momentum z=%.1f vz=%.2f %s (%d ms)%n", z0, vz, r == null ? "NONE" : r.ticks() + "t " + r, (System.nanoTime() - t0) / 1_000_000);
            }
        }
    }

    /**
     * drill_hh neo_3wall. The runway is three wide and the wall runs three back to the west, so the east edge is the
     * way around. The 1-wide aligned probe above cannot step sideways before the wall; this one can.
     */
    @Test
    public void courseNeo3AroundTheEastEdge() {
        BoxWorld w = new BoxWorld()
                .fill(-1, -1, 0, 1, -1, 2, BoxWorld.FULL)
                .fill(-3, -1, 3, 0, 1, 5, BoxWorld.FULL)
                .put(0, -1, 6, BoxWorld.FULL);
        TasBrute.Landing goal = new TasBrute.Landing(0, 0, 6);
        double[][] starts = {{0.5, 1.5, 0}, {1.5, 2.0, 0}, {0.5, 1.5, 0.28}, {1.5, 2.0, 0.28}};
        for (double[] s0 : starts) {
            PlayerSim s = standing(w, s0[0], 0, s0[1]);
            s.sprinting = s0[2] > 0;
            s.vz = s0[2];
            TasBrute b = new TasBrute(w);
            b.allowBack = true;
            b.maxTurn = 32;
            b.beam = 6000;
            b.maxTicks = 48;
            long t0 = System.nanoTime();
            TasBrute.Result r = b.search(s, 0, goal);
            System.out.printf("TAS course-neo3 x=%.1f z=%.1f vz=%.2f %s (%d ms)%n", s0[0], s0[1], s0[2], r == null ? "NONE" : r.ticks() + "t " + r, (System.nanoTime() - t0) / 1_000_000);
            assertNotNull(r);
        }
    }

    /** The same course blocks, flown by the two-yaw plan the executor actually searches. */
    @Test
    public void courseNeo3JumpSearchUnderTheLookCap() {
        BoxWorld w = new BoxWorld()
                .fill(-1, -1, 0, 1, -1, 2, BoxWorld.FULL)
                .fill(-3, -1, 3, 0, 1, 5, BoxWorld.FULL)
                .put(0, -1, 6, BoxWorld.FULL);
        JumpSearch js = new JumpSearch(w);
        js.dirX = 0;
        js.dirZ = 1;
        js.edge = 3;
        js.destX = 0;
        js.destY = 0;
        js.destZ = 6;
        js.slewYaw = true;
        for (float face : new float[]{0f, 70f}) {
            for (double x0 : new double[]{0.5, 1.5}) {
                js.faceYaw = face;
                PlayerSim s = standing(w, x0, 0, x0 == 0.5 ? 1.5 : 2.0);
                long t0 = System.nanoTime();
                boolean ok = js.search(s, false);
                System.out.printf("JS course-neo3 face=%.0f x=%.1f %s ticks=%d miss=%.3f (%d ms)%n", face, x0, ok ? "ok" : "NONE", js.ticks, js.miss, (System.nanoTime() - t0) / 1_000_000);
                if (x0 == 1.5) {
                    assertTrue("east edge of the runway, face " + face, ok);
                }
            }
        }
    }

    /** drill_hh headhitter_gap2: a 3-forward jump under one ceiling block at the lip. */
    @Test
    public void headHitterGap2UnderTheLookCap() {
        for (boolean roof : new boolean[]{false, true}) {
            BoxWorld w = new BoxWorld().fill(-1, -1, 0, 1, -1, 2, BoxWorld.FULL).put(0, -1, 5, BoxWorld.FULL);
            if (roof) {
                w.put(0, 2, 2, BoxWorld.FULL);
            }
            JumpSearch js = new JumpSearch(w);
            js.dirX = 0;
            js.dirZ = 1;
            js.edge = 3;
            js.destX = 0;
            js.destY = 0;
            js.destZ = 5;
            js.slewYaw = true;
            for (float face : new float[]{0f, 70f}) {
                for (double z0 : new double[]{0.5, 1.2, 2.2}) {
                    js.faceYaw = face;
                    PlayerSim s = standing(w, 0.5, 0, z0);
                    boolean ok = js.search(s, false);
                    System.out.printf("JS hh-gap2 roof=%b face=%.0f z=%.1f %s ticks=%d miss=%.3f%n", roof, face, z0, ok ? "ok" : "NONE", ok ? js.ticks : 0, ok ? js.miss : 0);
                    if (roof && face == 0f && z0 == 2.2) {
                        assertFalse("already under the lip and facing it, the two-yaw grid does not clear", ok);
                    } else {
                        assertTrue("roof=" + roof + " face=" + face + " z=" + z0, ok);
                    }
                }
            }
        }
    }

    /**
     * Ground friction is 0.546 a tick. A sprint jump still has enough speed to jump again if the next jump is pressed
     * on the landing tick; three ticks of no keys (the old settle) leaves a walk. MovementJump hands a centered fast
     * landing straight to the next movement for that reason.
     */
    @Test
    public void landingSpeedSurvivesOneJumpAndDiesIfItWaits() {
        BoxWorld flat = new BoxWorld().fill(0, -1, -4, 0, -1, 8, BoxWorld.FULL);
        PlayerSim kept = standing(flat, 0.5, 0, 0.5);
        kept.sprinting = true;
        kept.vz = 0.28;
        boolean jumped = false;
        double keptSpeed = 0;
        for (int t = 0; t < 40; t++) {
            boolean jump = kept.onGround && kept.jumpTicks == 0;
            kept.tick(0, 1, true, jump);
            if (jump) jumped = true;
            if (jumped && kept.onGround && kept.jumpTicks == 0) {
                keptSpeed = Math.hypot(kept.vx, kept.vz);
                break;
            }
        }
        PlayerSim waited = new PlayerSim(flat).copyFrom(kept);
        for (int t = 0; t < 3; t++) waited.tick(0, 0, false, false);
        double waitedSpeed = Math.hypot(waited.vx, waited.vz);
        System.out.printf("carry landing=%.3f after3=%.3f%n", keptSpeed, waitedSpeed);
        org.junit.Assert.assertTrue("a chained landing stays above a walk", keptSpeed > 0.25);
        org.junit.Assert.assertTrue("three idle ticks drop it under a walk", waitedSpeed < 0.08);
    }

    @Test
    public void alignedNeo1and2() {
        for (int len = 1; len <= 2; len++) {
            BoxWorld w = new BoxWorld().fill(0, -1, -3, 0, -1, 0, BoxWorld.FULL).fill(0, 0, 1, 0, 1, len, BoxWorld.FULL).put(0, -1, len + 1, BoxWorld.FULL);
            assertNotNull(run("neo" + len + "-aligned", w, standing(w, 0.5, 0, -2.5), 0, new TasBrute.Landing(0, 0, len + 1), 30));
        }
    }

    /**
     * Practice parkour (goals 25, 55, 67): a 2 long run-up, a 1 wide 3 high pillar in line and the landing behind it.
     * Every a=2 flat neo template, its plan searched from its own run-up start as the executor does.
     */
    @Test
    public void neo1ShortRunUp() {
        BoxWorld w = new BoxWorld().fill(0, -1, -1, 0, -1, 0, BoxWorld.FULL).fill(0, -1, 1, 0, 1, 1, BoxWorld.FULL).put(0, -1, 2, BoxWorld.FULL);
        for (JumpTemplates.Template t : JumpTemplates.ALL) {
            if (t.a != 2 || t.dy != 0 || t.b != 0 || !t.neo) continue;
            for (int s : new int[]{1, -1}) {
                int[] f = {0, 1, -s, 0, s};
                double lat = Math.min(t.lateral, 0.75), a = t.startA(), b = 0.5 + lat;
                for (boolean sprint : new boolean[]{false, true}) {
                    JumpSearch js = new JumpSearch(w);
                    js.dirX = 0;
                    js.dirZ = 1;
                    js.edge = 1;
                    js.destX = 0;
                    js.destY = 0;
                    js.destZ = 2;
                    js.side = s;
                    System.arraycopy(t.plan, 0, js.plan, 0, JumpSearch.DIMS);
                    PlayerSim st = standing(w, 0.5 + (b - 0.5) * f[2], 0, 0.5 + (a - 0.5));
                    st.sprinting = sprint;
                    boolean local = js.search(st, true);
                    boolean full = local || js.search(standing(w, st.x, 0, st.z), false);
                    System.out.printf("NEO1 r%d lat=%.2f back=%.2f side=%d sprint=%b start=%.2f,%.2f local=%b full=%b%n", t.runUp, t.lateral, t.back, s, sprint, st.x, st.z, local, full);
                }
            }
        }
        for (double x : new double[]{0.5, 0.6, 0.7}) {
            run("neo1-short x=" + x, w, standing(w, x, 0, -0.69), 0, new TasBrute.Landing(0, 0, 2), 30);
        }
    }

    /**
     * Practice parkour goal 29: off a lone block whose front edge carries a ladder (its plate sticks out 3/16) over a
     * 5 gap onto a block 2 down. No run-up: the block before it is reached by a jump.
     */
    @Test
    public void ladderLedgeGap5Down2() {
        double plate = 3 / 16.0;
        for (boolean ladder : new boolean[]{true, false}) {
            BoxWorld w = new BoxWorld().put(0, -1, 0, BoxWorld.FULL).put(0, -3, 6, BoxWorld.FULL);
            if (ladder) w.ladder(0, -1, 1, new double[][]{{0, 0, 0, 1, 1, plate}});
            for (double z : new double[]{0.31, 0.5}) {
                TasBrute b = new TasBrute(w);
                b.maxTurn = 30;
                b.floorY = -4;
                long t0 = System.nanoTime();
                TasBrute.Result r = b.search(standing(w, 0.5, 0, z), 0, new TasBrute.Landing(0, -2, 6));
                System.out.printf("TAS ledge ladder=%b z=%.2f %s (%d ms)%n", ladder, z, r == null ? "NONE" : r.ticks() + "t " + r, (System.nanoTime() - t0) / 1_000_000);
                for (JumpTemplates.Template t : JumpTemplates.ALL) {
                    if (t.a != 6 || t.dy != -2 || t.b != 0) continue;
                    JumpSearch js = new JumpSearch(w);
                    js.dirX = 0;
                    js.dirZ = 1;
                    js.edge = 1;
                    js.destX = 0;
                    js.destY = -2;
                    js.destZ = 6;
                    js.side = 1;
                    js.ledge = true;
                    System.arraycopy(t.plan, 0, js.plan, 0, JumpSearch.DIMS);
                    boolean local = js.search(standing(w, 0.5, 0, z), true);
                    boolean full = local || js.search(standing(w, 0.5, 0, z), false);
                    System.out.printf("JS ledge ladder=%b z=%.2f seed r%d local=%b full=%b%n", ladder, z, t.runUp, local, full);
                }
            }
        }
    }

    /**
     * Practice parkour goal 33: a row of free-standing 1 tall ladders, 4 apart, plates on the west side of their cells.
     * Hanging on one or standing on its plate top, onto the next one: hanging on it or standing on its top.
     */
    @Test
    public void floatingLadderRow() {
        double plate = 3 / 16.0;
        BoxWorld w = new BoxWorld().ladder(0, 0, 0, new double[][]{{0, 0, 0, plate, 1, 1}}).ladder(0, 0, 4, new double[][]{{0, 0, 0, plate, 1, 1}});
        for (int k = 0; k < 4; k++) {
            boolean onTop = k >= 2;
            TasBrute.Landing goal = (k & 1) == 0 ? new TasBrute.Landing(0, 0, 4).grab() : new TasBrute.Landing(0, 1, 4);
            PlayerSim s = new PlayerSim(w);
            s.x = onTop ? plate + 0.25 : plate + PlayerSim.HALF_WIDTH + 0.0125;
            s.y = onTop ? 1 : 0.5;
            s.z = 0.5;
            s.vy = onTop ? -0.0784000015258789 : 0;
            s.onGround = onTop;
            TasBrute b = new TasBrute(w);
            b.maxTicks = 40;
            b.maxTurn = 30;
            b.floorY = -2;
            long t0 = System.nanoTime();
            TasBrute.Result r = b.search(s, 0, goal);
            System.out.printf("TAS ladder-row %s -> %s %s (%d ms)%n", onTop ? "top" : "hang", (k & 1) == 0 ? "grab" : "top", r == null ? "NONE" : r.ticks() + "t " + r, (System.nanoTime() - t0) / 1_000_000);
        }
        // off the row's last ladder top sideways onto a block 4 east (its plate's back to it), from rest or still moving along the row
        BoxWorld w2 = new BoxWorld().ladder(0, 0, 0, new double[][]{{0, 0, 0, plate, 1, 1}}).put(4, 0, 0, BoxWorld.FULL);
        for (double vz : new double[]{0, 0.15}) {
            PlayerSim s = new PlayerSim(w2);
            s.x = plate + 0.25;
            s.y = 1;
            s.z = 0.5;
            s.vz = vz;
            s.vy = -0.0784000015258789;
            s.onGround = true;
            TasBrute b = new TasBrute(w2);
            b.maxTicks = 40;
            b.maxTurn = 45;
            b.floorY = -2;
            TasBrute.Result r = b.search(s, -90, new TasBrute.Landing(4, 1, 0));
            System.out.printf("TAS ladder-row top -> block east vz=%.2f %s%n", vz, r == null ? "NONE" : r.ticks() + "t " + r);
        }
    }

    /** Practice parkour hard course: single candle to single candle, 3 apart (centre to centre), same height. */
    @Test
    public void candleToCandle() {
        BoxWorld w = new BoxWorld().put(0, 0, 0, BoxWorld.CANDLE).put(-3, 0, 0, BoxWorld.CANDLE);
        assertNotNull(run("candle3", w, standing(w, 0.5, 6 / 16.0, 0.5), 90, new TasBrute.Landing(-3, 1, 0), 30));
    }

    /** The planner's plan grid (JumpSearch) on the same candle jump. */
    @Test
    public void candleJumpSearch() {
        BoxWorld w = new BoxWorld().put(0, 0, 0, BoxWorld.CANDLE).put(-3, 0, 0, BoxWorld.CANDLE).put(0, 0, -3, BoxWorld.CANDLE).put(-2, 0, 2, BoxWorld.CANDLE);
        int[][] cases = {{-1, 0, -3, 0}, {0, -1, 0, -3}};
        for (int[] c : cases) {
            JumpSearch js = new JumpSearch(w);
            js.dirX = c[0];
            js.dirZ = c[1];
            js.edge = (0.5 + 0.5 * c[0]) * c[0] + (0.5 + 0.5 * c[1]) * c[1];
            js.destX = c[2];
            js.destY = 1;
            js.destZ = c[3];
            js.side = 1;
            long t0 = System.nanoTime();
            boolean ok = js.search(standing(w, 0.5, 6 / 16.0, 0.5), false);
            long t1 = System.nanoTime();
            js.search(standing(w, 0.5, 6 / 16.0, 0.5), true);
            System.out.printf("JS timing full=%.1fms local=%.1fms%n", (t1 - t0) / 1e6, (System.nanoTime() - t1) / 1e6);
            StringBuilder p = new StringBuilder();
            for (int k : js.plan) p.append(k);
            System.out.println("JS candle dir=" + c[0] + "," + c[1] + " found=" + ok + " plan=" + p + " ticks=" + js.ticks);
        }
    }

    /**
     * Practice parkour start course: off a 1x1 pillar top onto a 2x2 slime pad 10 below, off to the side (2-3 blocks
     * left, 5-6 ahead), bouncing onto a block 3 further on. The plan grid MovementSlime searches, then the TAS.
     */
    @Test
    public void angledSlimeBounce() {
        BoxWorld w = new BoxWorld().fill(0, -5, 0, 0, -1, 0, BoxWorld.FULL)
                .slime(-3, -10, 5).slime(-3, -10, 6).slime(-2, -10, 5).slime(-2, -10, 6)
                .put(-3, -4, 8, BoxWorld.FULL).put(1, -4, 9, BoxWorld.FULL);
        JumpSearch js = new JumpSearch(w);
        js.dirX = 0;
        js.dirZ = 1;
        js.edge = 1;
        js.side = 1;
        js.bounceY = -9;
        js.anyDest = true;
        int[] p = new int[JumpSearch.DIMS];
        java.util.Set<String> found = new java.util.TreeSet<>();
        int runs = 0;
        long t0 = System.nanoTime();
        for (p[0] = 0; p[0] < JumpSearch.O0.length; p[0]++) for (p[3] = 0; p[3] < JumpSearch.O1.length; p[3]++)
            for (p[4] = 0; p[4] < JumpSearch.SWITCH.length; p[4]++) {
                if (Math.abs(JumpSearch.O0[p[0]] - JumpSearch.O1[p[3]]) > 30) continue;
                for (int nj = 1; nj >= 0; nj--) for (p[2] = 0; p[2] < (nj == 1 ? 1 : JumpSearch.PLAIN_EDGES); p[2]++)
                    for (p[5] = 0; p[5] < JumpSearch.RELEASE.length; p[5]++) for (p[6] = 0; p[6] < JumpSearch.BRAKE.length; p[6]++) {
                        if (p[5] == 0 && p[6] == 1) continue;
                        js.noJump = nj == 1;
                        js.destY = -9;
                        runs++;
                        if (js.run(standing(w, 0.5, 0, 0.5), p, false, 0, null) && js.destY > -9) {
                            found.add(js.destX + "," + js.destY + "," + js.destZ);
                        }
                    }
            }
        System.out.printf("SLIME grid: %d runs, %d ms, landings %s%n", runs, (System.nanoTime() - t0) / 1_000_000, found);
        TasBrute b = new TasBrute(w);
        b.maxTicks = 80;
        b.maxTurn = 30;
        TasBrute.Result r = b.search(standing(w, 0.5, 0, 0.5), 0, new TasBrute.Landing(-3, -3, 8));
        System.out.println("SLIME tas -> -3,-3,8: " + (r == null ? "NONE" : r.ticks() + "t " + r));
        org.junit.Assert.assertTrue(found.contains("-3,-3,8") || found.contains("1,-3,9"));
    }
}
