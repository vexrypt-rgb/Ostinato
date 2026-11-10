/*
 * Ostinato: new file (predictive physics engine). Not part of upstream Baritone.
 */
package baritone.physics;

import baritone.api.physics.BodyState;
import baritone.api.physics.PredictionRequest.Input;

/**
 * Ostinato: one tick of a living subject, vanilla 26.3: Entity.baseTick's fluid update, LivingEntity.aiStep (small
 * motion cut, input, jumping, travel in air, fluid or flight, block effects) with LocalPlayer's input shaping and
 * flying keys and Player's swim and fly rules. Entity.move is {@link Body}'s. Input is held for the whole horizon.
 * Mutable; one simulation owns it.
 */
final class LivingSim {
    /** Abilities.getFlyingSpeed's default; the engine does not read a changed one. */
    private static final float FLYING_SPEED = 0.05f;

    final Body b = new Body();
    private boolean sprinting, swimming, dolphin, jumping, crouching, sneakKey;
    private float speed, jumpStrength, frictionMod, airDragMod, waterEff;
    private int jumpBoost, levitation, noJumpDelay;
    private float yaw, pitch, xxa, zza;

    LivingSim set(BodyState s, Input in) {
        b.set(s);
        yaw = Float.isNaN(in.yaw) ? s.yaw : in.yaw;
        pitch = Float.isNaN(in.pitch) ? s.pitch : in.pitch;
        boolean wasSprinting = s.has(BodyState.SPRINTING);
        swimming = s.has(BodyState.SWIMMING);
        dolphin = s.has(BodyState.DOLPHINS_GRACE);
        speed = s.speed;
        if (b.player) {
            sprinting = in.sprint && in.forward > 0;
            // the sprint modifier is ADD_MULTIPLIED_TOTAL (double) 0.3f on movement speed, worked out in doubles and read
            // back as a float (Player.getSpeed): toggling it scales the captured value the same way
            if (sprinting && !wasSprinting) speed = (float) (speed * (1.0 + (double) 0.3f));
            else if (!sprinting && wasSprinting) speed = (float) (speed / (1.0 + (double) 0.3f));
            sneakKey = in.sneak;
            b.sneaking = in.sneak && !b.flying;
            crouching = b.sneaking && !swimming && !b.fallFlying;
        } else {
            sprinting = wasSprinting;
            sneakKey = false;
            crouching = b.sneaking;
        }
        jumpStrength = s.jumpStrength;
        frictionMod = s.frictionModifier;
        airDragMod = s.airDragModifier;
        waterEff = s.waterMovementEfficiency;
        jumpBoost = s.jumpBoost;
        levitation = s.levitation;
        noJumpDelay = 0;
        jumping = in.jump;
        if (b.player) {
            // KeyboardInput: Vec2(left, forward).normalized(), then LocalPlayer.modifyInput
            float mx = in.strafe, mz = in.forward;
            float len = (float) Math.sqrt(mx * mx + mz * mz);
            if (len < 1.0E-4f) {
                mx = 0;
                mz = 0;
            } else {
                mx /= len;
                mz /= len;
            }
            if (mx * mx + mz * mz != 0.0f) {
                mx *= 0.98f;
                mz *= 0.98f;
                if (crouching) {
                    mx *= s.sneakingSpeed;
                    mz *= s.sneakingSpeed;
                }
                float l = (float) Math.sqrt(mx * mx + mz * mz);
                if (l > 0.0f) {
                    float dx = mx * (1.0f / l), dz = mz * (1.0f / l);
                    float ax = Math.abs(dx), az = Math.abs(dz);
                    float tan = az > ax ? ax / az : az / ax;
                    float toSquare = (float) Math.sqrt((float) (1.0f + tan * tan));
                    float m = Math.min(l * toSquare, 1.0f);
                    mx = dx * m;
                    mz = dz * m;
                }
            }
            xxa = mx;
            zza = mz;
        } else {
            xxa = in.strafe * 0.98f;
            zza = in.forward * 0.98f;
        }
        return this;
    }

    void tick(TerrainView v) {
        b.startTick();
        // Entity.baseTick
        b.updateFluids(v);
        if (b.inLava) b.fallDistance *= 0.5;
        // LocalPlayer.aiStep, before LivingEntity.aiStep: flying up and down
        if (b.player && b.flying) {
            int dir = (sneakKey ? -1 : 0) + (jumping ? 1 : 0);
            if (dir != 0) b.vy += (double) ((float) dir * FLYING_SPEED * 3.0f);
        }
        // LivingEntity.aiStep
        if (noJumpDelay > 0) noJumpDelay--;
        if (b.player) {
            if (b.vx * b.vx + b.vz * b.vz < 9.0E-6) {
                b.vx = 0;
                b.vz = 0;
            }
        } else {
            if (Math.abs(b.vx) < 0.003) b.vx = 0;
            if (Math.abs(b.vz) < 0.003) b.vz = 0;
        }
        if (Math.abs(b.vy) < 0.003) b.vy = 0;
        boolean affectedByFluids = !(b.player && b.flying);
        if (jumping && affectedByFluids) {
            double fh = b.inLava ? b.lavaHeight : b.waterHeight;
            boolean inWaterH = b.inWater && fh > 0.0;
            double threshold = jumpThreshold();
            if (inWaterH && (!b.onGround || fh > threshold)) {
                b.vy += (double) 0.04f;
            } else if (b.inLava && !(b.onGround && b.lavaHeight <= threshold)) {
                b.vy += (double) 0.04f;
            } else if ((b.onGround || inWaterH && fh <= threshold) && noJumpDelay == 0) {
                jumpFromGround(v);
                noJumpDelay = 10;
            }
        } else {
            noJumpDelay = 0;
        }
        if (b.fallFlying) {
            if (b.vy > -0.5 && b.fallDistance > 1.0) b.fallDistance = 1.0; // checkFallDistanceAccumulation
            if (b.onGround || levitation > 0) b.fallFlying = false; // canGlide (server)
        }
        if (b.slowFalling || levitation > 0) b.resetFallDistance();
        travel(v);
        b.applyEffectsFromBlocks(v);
    }

    private double jumpThreshold() {
        return b.eyeHeight < 0.4 ? 0.0 : 0.4;
    }

    private void jumpFromGround(TerrainView v) {
        float power = jumpStrength * 1.0f * b.blockJumpFactor(v) + (jumpBoost > 0 ? 0.1f * (float) jumpBoost : 0.0f);
        if (power <= 1.0E-5f) return;
        b.vy = Math.max((double) power, b.vy);
        if (sprinting) {
            float angle = yaw * ((float) Math.PI / 180);
            b.vx += (double) (-PhysMath.sin(angle)) * 0.2;
            b.vz += (double) PhysMath.cos(angle) * 0.2;
        }
    }

    /** Player.travel around LivingEntity.travel. */
    private void travel(TerrainView v) {
        if (b.player && swimming) {
            double lookY = -PhysMath.sin(pitch * ((float) Math.PI / 180));
            double mult = lookY < -0.2 ? 0.085 : 0.06;
            if (lookY <= 0.0 || jumping || v.at(PhysMath.floor(b.x), PhysMath.floor(b.y + 1.0 - 0.1), PhysMath.floor(b.z)).fluid != BlockInfo.FLUID_NONE) {
                b.vy += (lookY - b.vy) * mult;
            }
        }
        if (b.player && b.flying) {
            double oldVy = b.vy;
            livingTravel(v);
            b.vy = oldVy * 0.6;
        } else {
            livingTravel(v);
        }
    }

    private void livingTravel(TerrainView v) {
        boolean affectedByFluids = !(b.player && b.flying);
        if ((b.inWater || b.inLava) && affectedByFluids) travelInFluid(v);
        else if (b.fallFlying) travelFallFlying(v);
        else travelInAir(v);
    }

    private void travelInAir(TerrainView v) {
        long below = b.onPos(v, 0.500001f);
        int bx = PhysMath.unpackX(below), by = PhysMath.unpackY(below), bz = PhysMath.unpackZ(below);
        float blockFriction = b.onGround ? PhysMath.modifiedFriction(v.at(bx, by, bz).friction, frictionMod) : 1.0f;
        moveRelative(frictionSpeed(blockFriction));
        handleOnClimbable(v);
        b.move(v, b.vx, b.vy, b.vz);
        double mx = b.vx, my = b.vy, mz = b.vz;
        if ((b.hColl || jumping) && onClimbable(v)) my = 0.2;
        if (levitation > 0) my += (0.05 * (double) levitation - my) * 0.2;
        else if (b.player && v.unloadedAt(bx, by, bz)) my = b.y > (double) v.terrain().minY ? -0.1 : 0.0;
        else my -= b.effectiveGravity();
        float airDrag = PhysMath.modifiedFriction(0.91f, airDragMod);
        float friction = blockFriction * airDrag;
        float vertical = PhysMath.modifiedFriction(0.98f, airDragMod);
        b.vx = mx * (double) friction;
        b.vy = my * (double) vertical;
        b.vz = mz * (double) friction;
    }

    private float frictionSpeed(float blockFriction) {
        if (b.onGround) return (double) blockFriction > 0.6 ? speed * (0.21600002f / (blockFriction * blockFriction * blockFriction)) : speed;
        if (b.player) {
            if (b.flying) return sprinting ? FLYING_SPEED * 2.0f : FLYING_SPEED;
            return sprinting ? 0.025999999f : 0.02f;
        }
        return 0.02f;
    }

    /** Entity.moveRelative with the (xxa, 0, zza) input. */
    private void moveRelative(float s) {
        double ix = xxa, iz = zza;
        double lenSq = ix * ix + iz * iz;
        if (lenSq < 1.0E-7) return;
        if (lenSq > 1.0) {
            double len = Math.sqrt(lenSq);
            ix /= len;
            iz /= len;
        }
        ix *= s;
        iz *= s;
        float angle = yaw * ((float) Math.PI / 180);
        float sin = PhysMath.sin(angle), cos = PhysMath.cos(angle);
        b.vx += ix * (double) cos - iz * (double) sin;
        b.vz += iz * (double) cos + ix * (double) sin;
    }

    private boolean onClimbable(TerrainView v) {
        BlockInfo feet = v.at(PhysMath.floor(b.x), PhysMath.floor(b.y), PhysMath.floor(b.z));
        if (b.fallFlying && feet.is(BlockInfo.GLIDE_THROUGH)) return false;
        return feet.is(BlockInfo.CLIMBABLE);
    }

    private void handleOnClimbable(TerrainView v) {
        if (!onClimbable(v)) return;
        b.resetFallDistance();
        b.vx = PhysMath.clamp(b.vx, (double) -0.15f, (double) 0.15f);
        b.vz = PhysMath.clamp(b.vz, (double) -0.15f, (double) 0.15f);
        double yd = Math.max(b.vy, (double) -0.15f);
        BlockInfo feet = v.at(PhysMath.floor(b.x), PhysMath.floor(b.y), PhysMath.floor(b.z));
        boolean scaffolding = feet.is(BlockInfo.CLIMBABLE) && feet.is(BlockInfo.CONTEXT);
        if (yd < 0.0 && !scaffolding && b.player && sneakKey && !b.flying) yd = 0.0;
        b.vy = yd;
    }

    private void travelFallFlying(TerrainView v) {
        if (onClimbable(v)) {
            travelInAir(v);
            b.fallFlying = false;
            return;
        }
        // updateFallFlyingMovement
        float xRad = pitch * ((float) Math.PI / 180), yRad = -yaw * ((float) Math.PI / 180);
        float yCos = PhysMath.cos(yRad), ySin = PhysMath.sin(yRad), xCos = PhysMath.cos(xRad);
        double lx = ySin * xCos, lz = yCos * xCos; // look vector (its y is not used here)
        double lookH = Math.sqrt(lx * lx + lz * lz);
        double mx = b.vx, my = b.vy, mz = b.vz;
        double moveH = Math.sqrt(mx * mx + mz * mz);
        double g = b.effectiveGravity();
        double c = Math.cos(xRad);
        double lift = c * c;
        my += g * (-1.0 + lift * 0.75);
        if (my < 0.0 && lookH > 0.0) {
            double convert = my * -0.1 * lift;
            mx += lx * convert / lookH;
            my += convert;
            mz += lz * convert / lookH;
        }
        if (xRad < 0.0f && lookH > 0.0) {
            double convert = moveH * (double) (-PhysMath.sin(xRad)) * 0.04;
            mx += -lx * convert / lookH;
            my += convert * 3.2;
            mz += -lz * convert / lookH;
        }
        if (lookH > 0.0) {
            mx += (lx / lookH * moveH - mx) * 0.1;
            mz += (lz / lookH * moveH - mz) * 0.1;
        }
        b.vx = mx * (double) 0.99f;
        b.vy = my * (double) 0.98f;
        b.vz = mz * (double) 0.99f;
        b.move(v, b.vx, b.vy, b.vz);
    }

    private void travelInFluid(TerrainView v) {
        boolean falling = b.vy <= 0.0;
        double oldY = b.y;
        double g = b.effectiveGravity();
        if (b.inWater) {
            float slowDown = sprinting ? 0.9f : 0.8f;
            float s = 0.02f;
            float ww = waterEff;
            if (!b.onGround) ww *= 0.5f;
            if (ww > 0.0f) {
                slowDown += (0.54600006f - slowDown) * ww;
                s += (speed - s) * ww;
            }
            if (dolphin) slowDown = 0.96f;
            moveRelative(s);
            b.move(v, b.vx, b.vy, b.vz);
            double my = b.vy;
            if (b.hColl && onClimbable(v)) my = 0.2;
            b.vx *= slowDown;
            b.vy = fluidFalling(g, falling, my * (double) 0.8f);
            b.vz *= slowDown;
        } else {
            moveRelative(0.02f);
            b.move(v, b.vx, b.vy, b.vz);
            if (b.lavaHeight <= jumpThreshold()) {
                b.vx *= 0.5;
                b.vz *= 0.5;
                b.vy = fluidFalling(g, falling, b.vy * (double) 0.8f);
            } else {
                b.vx *= 0.5;
                b.vy *= 0.5;
                b.vz *= 0.5;
            }
            if (g != 0.0) b.vy += -g / 4.0;
        }
        // jumpOutOfFluid
        double dy = b.vy + (double) 0.6f - b.y + oldY;
        if (b.hColl && !v.blocked(b.x - b.halfW + b.vx, b.y + dy, b.z - b.halfW + b.vz,
                b.x + b.halfW + b.vx, b.y + b.height + dy, b.z + b.halfW + b.vz)) {
            b.vy = (double) 0.3f;
        }
    }

    /** LivingEntity.getFluidFallingAdjustedMovement's y. */
    private double fluidFalling(double g, boolean falling, double y) {
        if (g == 0.0 || sprinting) return y;
        return falling && Math.abs(y - 0.005) >= 0.003 && Math.abs(y - g / 16.0) < 0.003 ? -0.003 : y - g / 16.0;
    }
}
