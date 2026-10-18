/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Ostinato: a per-tick trace of everything that can make two runs of the same drill part ways, so a headless client
 * and a real one can be compared tick by tick (tools/paritydiff.py finds the first tick that differs). Off unless
 * -Dostinato.trace=FILE is set. Floats are written in full (Float/Double.toString round-trips), so equal lines mean
 * equal state.
 * <pre>
 * t  client tick (counted from the first tick in a level) | st the integrated server's tick count, read across threads
 * fr frames rendered since the last tick (never compared: it is the frame rate) | r0 the rotation as the tick began
 * me pos, velocity, rotation, onGround, hurtTime, health | in the key presses the client's input carried
 * f  keys Ostinato forced | dec PvpProcess's action | sl selected slot | cd attack strength | hd tremor draws
 * tg target id, pos, velocity, hurtTime, health (the nearest living entity within 16 blocks when no fight)
 * </pre>
 */
public final class ParityTrace implements AbstractGameEventListener {

    private static final String FILE = System.getProperty("ostinato.trace", "");
    /** Frames rendered since the last client tick; counted by the frame hook. */
    public static int frames;

    private final Baritone baritone;
    private BufferedWriter out;
    private int t;
    private String r0 = "-";

    private ParityTrace(Baritone baritone) {
        this.baritone = baritone;
    }

    public static void install(Baritone baritone) {
        if (!FILE.isEmpty()) baritone.getGameEventHandler().registerEventListener(new ParityTrace(baritone));
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() != TickEvent.Type.IN) return;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) r0 = p.getYRot() + "," + p.getXRot();
    }

    @Override
    public void onPostTick(TickEvent event) {
        if (event.getType() != TickEvent.Type.IN) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        try {
            if (out == null) {
                Path f = Paths.get(FILE);
                if (f.getParent() != null) Files.createDirectories(f.getParent());
                out = Files.newBufferedWriter(f, StandardCharsets.UTF_8);
                out.write("#trace seed=" + System.getProperty("ostinato.seed", "-") + " frameLook=" + Baritone.settings().frameLook.value + " lockstep=" + Lockstep.ENABLED + "\n");
            }
            MinecraftServer server = mc.getSingleplayerServer();
            StringBuilder sb = new StringBuilder(320);
            sb.append("t=").append(t++).append(" st=").append(server == null ? -1 : server.getTickCount()).append(" fr=").append(frames);
            frames = 0;
            sb.append(" r0=").append(r0);
            sb.append(" me=").append(vec(p.position())).append(" v=").append(vec(p.getDeltaMovement()))
                    .append(" r=").append(p.getYRot()).append(',').append(p.getXRot())
                    .append(" g=").append(p.onGround() ? 1 : 0).append(" ht=").append(p.hurtTime).append(" hp=").append(p.getHealth());
            sb.append(" in=").append(p.input == null ? "-" : keys(p.input.keyPresses));
            sb.append(" f=");
            for (Input i : Input.values()) if (baritone.getInputOverrideHandler().isInputForcedDown(i)) sb.append(i.name().charAt(0)).append(i.ordinal());
            String dec = baritone.getPvpProcess() instanceof baritone.process.PvpProcess pp ? pp.traceDec() : "-";
            sb.append(" dec=").append(dec).append(" sl=").append(p.getInventory().getSelectedSlot()).append(" cd=").append(p.getAttackStrengthScale(0f));
            if (baritone.getLookBehavior() instanceof baritone.behavior.LookBehavior lb) {
                sb.append(" hd=").append(lb.handDraws);
                if (lb.traceTarget != null) sb.append(" lt=").append(lb.traceTarget.getYaw()).append(',').append(lb.traceTarget.getPitch());
                lb.traceTarget = null;
            }
            var path = baritone.getPathingBehavior().getPath();
            if (path.isPresent()) sb.append(" path=").append(path.get().length()).append(':').append(Integer.toHexString(path.get().positions().hashCode()))
                    .append('@').append(baritone.getPathingBehavior().getCurrent() == null ? -1 : baritone.getPathingBehavior().getCurrent().getPosition());
            if (Lockstep.ENABLED) sb.append(" lm=").append(Lockstep.misses).append(':').append(Lockstep.missPath).append(Lockstep.missC2s > 0 ? "c" + Lockstep.missC2s : "")
                    .append(Lockstep.missTick > 0 ? "t" + Lockstep.missTick : "").append(Lockstep.missS2c > 0 ? "s" + Lockstep.missS2c : "").append(Lockstep.missGate > 0 ? "g" + Lockstep.missGate : "");
            LivingEntity tg = nearest(mc, p);
            if (tg != null) sb.append(" tg=").append(tg.getId()).append(':').append(vec(tg.position())).append(" tv=").append(vec(tg.getDeltaMovement()))
                    .append(" tht=").append(tg.hurtTime).append(" thp=").append(tg.getHealth());
            // in lockstep the server is idle between our ticks, so its own view can be read here: where a run parts,
            // this says whether the server did something else or the client heard of it at another tick
            if (tg != null && server != null && Lockstep.engaged()) {
                for (net.minecraft.server.level.ServerLevel lvl : server.getAllLevels()) {
                    Entity se = lvl.getEntity(tg.getUUID());
                    if (se instanceof LivingEntity sl) {
                        sb.append(" s=").append(sl.hurtTime).append(':').append(sl.getHealth()).append(':').append(vec(sl.position()));
                        break;
                    }
                }
                net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayer(p.getUUID());
                if (sp != null) sb.append(" sme=").append(vec(sp.position())).append(" scd=").append(sp.getAttackStrengthScale(0f));
            }
            sb.append('\n');
            out.write(sb.toString());
            if (t % 20 == 0) out.flush();
        } catch (Exception e) {
            System.out.println("ParityTrace: " + e);
        }
    }

    private static String keys(net.minecraft.world.entity.player.Input k) {
        return (k.forward() ? "F" : "") + (k.backward() ? "B" : "") + (k.left() ? "L" : "") + (k.right() ? "R" : "")
                + (k.jump() ? "J" : "") + (k.shift() ? "S" : "") + (k.sprint() ? "P" : "");
    }

    private static String vec(Vec3 v) {
        return v.x + "," + v.y + "," + v.z;
    }

    private static LivingEntity nearest(Minecraft mc, LocalPlayer p) {
        LivingEntity best = null;
        double bd = 16 * 16;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == p || !(e instanceof LivingEntity l) || !l.isAlive()) continue;
            double d = e.distanceToSqr(p);
            if (d < bd) {
                bd = d;
                best = l;
            }
        }
        return best;
    }
}
