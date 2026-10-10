import pathlib
p = pathlib.Path(r"src/main/java/baritone/process/PvpProcess.java")
t = p.read_text(encoding="utf-8")
repls = []

repls.append(("phase1",
"""            if (macePhase == 1) { // rising: throw the charge under our feet near the apex
                maceTicks++;
                if (!select(me, wind)) return decide(\"swap\");
                if (maceTicks >= 2) {
                    if (!face(me.getYRot(), 90f, 2.5f)) return decide(\"mace\");
                    press(ctx.minecraft().options.keyUse);
                    macePhase = 2;
                    maceTicks = 0;
                } else {
                    look(target.getEyePosition());
                    key(Input.MOVE_FORWARD);
                    key(Input.SPRINT);
                }
                return decide(\"mace\");
            }""",
"""            if (macePhase == 1) { // hop: charge leaves straight down this tick, under the feet
                maceTicks++;
                if (!select(me, wind)) return decide(\"swap\");
                // Do not look at the target or walk in. A 2.5 deg/tick look throws into the ground ahead.
                if (maceTicks >= 2 && !me.onGround()) {
                    throwStraightDown(me);
                    macePhase = 2;
                    maceTicks = 0;
                } else if (maceTicks > 8 && me.onGround()) {
                    macePhase = 0;
                }
                return decide(\"mace\");
            }"""))

repls.append(("elytra",
"""                } else if (rocket < 0 && !boosted && maceTicks >= 2 && wind >= 0) {
                    if (!select(me, wind) || !face(me.getYRot(), 90f, 2.5f)) return decide(\"swap\");
                    press(ctx.minecraft().options.keyUse);
                    boosted = true;""",
"""                } else if (rocket < 0 && !boosted && maceTicks >= 2 && wind >= 0) {
                    if (!select(me, wind)) return decide(\"swap\");
                    throwStraightDown(me);
                    boosted = true;"""))

repls.append(("face",
"""    private boolean face(float yaw, float pitch, float tol) {
        baritone.getLookBehavior().updateTarget(new Rotation(yaw, pitch), true);
        Player me = ctx.player();
        return Math.abs(Mth.wrapDegrees(yaw - me.getYRot())) <= tol && Math.abs(pitch - me.getXRot()) <= tol;
    }
""",
"""    private boolean face(float yaw, float pitch, float tol) {
        baritone.getLookBehavior().updateTarget(new Rotation(yaw, pitch), true);
        Player me = ctx.player();
        return Math.abs(Mth.wrapDegrees(yaw - me.getYRot())) <= tol && Math.abs(pitch - me.getXRot()) <= tol;
    }

    /**
     * Foot wind-charge only. Pitch is set to 90 and use is pressed on this tick, before keybinds,
     * so the charge leaves straight down. A smoothed look makes it hit the ground ahead. Other aims stay human.
     */
    private void throwStraightDown(Player me) {
        float yaw = me.getYRot();
        me.setXRot(90f);
        baritone.getLookBehavior().updateTarget(new Rotation(yaw, 90f), true);
        press(ctx.minecraft().options.keyUse);
    }
"""))

repls.append(("tools",
"""        if (wind >= 0 && windCool == 0 && los && me.onGround() && hr > SPEAR_JAB_HI && hr <= 6.5
                && Math.abs(target.getY() - me.getY()) < 2.5) {
            Vec3 away = target.position().subtract(me.position());
            double len = Math.hypot(away.x, away.z);
            if (len > 0.1) {
                Vec3 at = target.position().add(away.x / len * 1.4, 0.4, away.z / len * 1.4);
                look(at);
                if (!select(me, wind)) return decide(\"swap\");
                if (!aimedAt(me, at, 10f)) return decide(\"wind\");
                press(ctx.minecraft().options.keyUse);
                windCool = 30;
                return decide(\"wind\");
            }
        }
        return null;""",
"""        // Too close to jab: one straight-down hop knocks them out toward the band. Never aim past their feet.
        if (wind >= 0 && windCool == 0 && los && me.onGround() && !me.isInWater()
                && hr < SPEAR_MIN && hr > 0.4 && Math.abs(target.getY() - me.getY()) < 1.5) {
            if (!select(me, wind)) return decide(\"swap\");
            throwStraightDown(me);
            windCool = 30;
            return decide(\"wind\");
        }
        return null;"""))

repls.append(("doc",
"""     * One wind charge aimed past the target knocks them into the 2.6-3.4 band.
     * A wind charge at our feet is only the launch for a mace smash onto a target just outside that band.
""",
"""     * A straight-down wind charge is a hop: a mace smash just outside the jab band, or a shove out of the dead zone.
     * It is not aimed past the target and it is not used to walk in.
"""))

for name, a, b in repls:
    if a not in t:
        raise SystemExit("MISSING " + name)
    t = t.replace(a, b, 1)
p.write_text(t, encoding="utf-8", newline="\n")
print("ok", p.stat().st_size)
