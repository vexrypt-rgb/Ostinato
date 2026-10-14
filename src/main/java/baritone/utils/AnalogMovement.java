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

/**
 * Ostinato: analog (controller-like) movement input, an optional refinement on top of the 8 key directions.
 * <p>
 * Key movement stays the primary path: every controller still works, and must work, with this off. When it is on,
 * the bot may hold any forward/strafe value in [-1, 1] for a tick, as Controlify feeds a thumbstick. The setting
 * {@code analogMovement} picks where: {@link Mode#OFF}, {@link Mode#SINGLEPLAYER_ONLY} (default: single-player and
 * LAN worlds this client hosts, never a remote server) or {@link Mode#ALWAYS}.
 * <p>
 * Pure (no Minecraft types) so the sim and tests can use it.
 */
public final class AnalogMovement {

    private AnalogMovement() {}

    public enum Mode {
        OFF, SINGLEPLAYER_ONLY, ALWAYS;

        /** Lenient: case and '-' / ' ' are ignored; anything unknown is OFF, the safe side. */
        public static Mode parse(String s) {
            if (s == null) return OFF;
            String n = s.trim().toUpperCase().replace('-', '_').replace(' ', '_');
            for (Mode m : values()) {
                if (m.name().equals(n)) return m;
            }
            if (n.equals("SINGLEPLAYER") || n.equals("SP") || n.equals("LOCAL")) return SINGLEPLAYER_ONLY;
            if (n.equals("ON") || n.equals("TRUE")) return ALWAYS;
            return OFF;
        }
    }

    /**
     * Whether analog values may be used. {@code localServer}: this client runs the world's server (single-player or a
     * LAN world it opened), i.e. {@code Minecraft.hasSingleplayerServer()}; false on any remote server.
     */
    public static boolean allowed(Mode mode, boolean localServer) {
        switch (mode) {
            case ALWAYS:
                return true;
            case SINGLEPLAYER_ONLY:
                return localServer;
            default:
                return false;
        }
    }

    /**
     * What a {@code ClientInput} hands over as moveVector for raw stick values: clamped to [-1, 1] and the length
     * limited (not normalised) to 1, as Controlify's ControllerPlayerMovement does. Returns {strafe, forward}.
     */
    public static float[] moveVector(float forward, float strafe) {
        float f = clamp(forward), s = clamp(strafe);
        float len = (float) Math.sqrt(s * s + f * f);
        if (len > 1) {
            s /= len;
            f /= len;
        }
        return new float[]{s, f};
    }

    /**
     * Vanilla 26.3 LocalPlayer.modifyInput on a moveVector {strafe, forward}: x0.98, the sneak factor (the
     * SNEAKING_SPEED attribute, 0.3 by default; pass 1 when not crouching), then modifyInputSpeedForSquareMovement,
     * which stretches the length by the distance to the unit square along that direction, capped at 1. Item-use
     * slowdown is not modelled. Returns {xxa, zza}.
     */
    public static float[] vanillaModify(float strafe, float forward, float sneakFactor) {
        if (strafe * strafe + forward * forward == 0.0F) return new float[]{0, 0};
        float x = strafe * 0.98F, y = forward * 0.98F;
        if (sneakFactor != 1) {
            x *= sneakFactor;
            y *= sneakFactor;
        }
        float length = (float) Math.sqrt(x * x + y * y); // Vec2.length
        if (length <= 0.0F) return new float[]{x, y};
        float dx = x * (1.0F / length), dy = y * (1.0F / length);
        float ax = Math.abs(dx), ay = Math.abs(dy);
        float tan = ay > ax ? ax / ay : ay / ax;
        float dist = (float) Math.sqrt(1.0F + tan * tan);
        float m = Math.min(length * dist, 1.0F);
        return new float[]{dx * m, dy * m};
    }

    /** Vanilla ClientInput.hasForwardImpulse: what starting and keeping a sprint need. No 0.8 threshold in 26.3. */
    public static boolean hasForwardImpulse(float forward) {
        return forward > 1.0E-5F;
    }

    private static float clamp(float v) {
        return Float.isNaN(v) ? 0 : Math.max(-1, Math.min(1, v));
    }
}
