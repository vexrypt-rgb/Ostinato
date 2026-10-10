from pathlib import Path
p = Path(r"C:\Users\redfa\Documents\MinecraftDev\Ostinato-combat-1.21.11\src\main\java\baritone\process\PvpProcess.java")
text = p.read_text(encoding="utf-8")
old = """            double er = exactReach(me, target);
            // Jab only in the middle of the 2-4 band. Outer edge (~4) misses; under SPEAR_MIN cannot connect.
            boolean inReach = spear >= 0
                    ? (er >= SPEAR_JAB_LO && er <= SPEAR_JAB_HI)
                    : (er <= reach - 0.05);
"""
new = """            double er = exactReach(me, target);
            // Movement uses horizontal distance to the hitbox. A mace hop makes the 3D eye distance
            // jump to ~5 while we are already in the jab band, which was the close/back oscillation.
            double hr = horizontalBoxDist(me, target);
            // Jab only in the middle of the 2-4 band. Outer edge (~4) misses; under SPEAR_MIN cannot connect.
            boolean inReach = spear >= 0
                    ? (hr >= SPEAR_JAB_LO && hr <= SPEAR_JAB_HI)
                    : (er <= reach - 0.05);
"""
assert text.count(old) == 1, "block1"
text = text.replace(old, new, 1)
old = """            // Spear jab: only the connectable middle of the band (~2.6-3.4). Aim first, then swing.
            // Hysteresis drives into that middle and holds; SPEAR_MIN stays 2; plain spears never lunge.
            if (spear >= 0) {
                if (spearBand > 0) {
                    if (er <= SPEAR_JAB_HI - 0.15) spearBand = 0; // walk in until ~3.25
                } else if (spearBand < 0) {
                    if (er >= SPEAR_JAB_LO + 0.15) spearBand = 0; // back out until ~2.75
                } else if (er < SPEAR_JAB_LO - 0.2 || er < SPEAR_MIN) {
                    spearBand = -1; // too close for a connectable jab
                } else if (er > SPEAR_JAB_HI + 0.2) {
                    spearBand = 1; // outside the connectable band, not a 3.4-edge flicker
                }
            }
            if (spear >= 0 && spearBand == 0 && los && cd >= 0.85f) {
                boolean fallReach = meFalling && er >= SPEAR_JAB_LO && er <= SPEAR_JAB_HI + 0.4;
                boolean antiAirReach = targetFalling && er >= SPEAR_JAB_LO && er <= SPEAR_JAB_HI + 0.6;
                if (inReach || fallReach || antiAirReach) {
                    if (!select(me, spear)) return decide("swap");
                    Vec3 aim = aimPoint(me, target);
                    if (targetFalling) aim = aim.add(tv().scale(Math.min(3.0, Math.abs(tv().y) * 4.0))); // lead the drop
                    look(aim);
                    if (!aimedAt(me, aim, 8f)) return decide("spear_aim"); // wait until the crosshair tracks
                    hit(me);
                    return decide(meFalling ? "spear_fall" : targetFalling ? "spear_air" : "spear");
                }
            }
"""
new = """            // Spear jab: only the connectable middle of the band (~2.6-3.4). Aim first, then swing.
            // Hysteresis is on horizontal distance so a jump does not flip close/back. SPEAR_MIN stays 2.
            // Plain spears never lunge. A jab is impossible below full charge (minimum_attack_charge = 1).
            if (spear >= 0) {
                if (spearBand > 0) {
                    if (hr <= SPEAR_JAB_HI - 0.15) spearBand = 0; // walk in until ~3.25
                } else if (spearBand < 0) {
                    if (hr >= SPEAR_JAB_LO + 0.15) spearBand = 0; // back out until ~2.75
                } else if (hr < SPEAR_JAB_LO - 0.2 || hr < SPEAR_MIN) {
                    spearBand = -1; // too close for a connectable jab
                } else if (hr > SPEAR_JAB_HI + 0.2) {
                    spearBand = 1; // outside the connectable band, not a 3.4-edge flicker
                }
            }
            ItemStack spearStack = spear >= 0 ? me.getInventory().getItem(spear) : ItemStack.EMPTY;
            boolean spearCharged = spear >= 0 && !me.cannotAttackWithItem(spearStack, 0);
            if (spear >= 0 && spearBand == 0 && los && spearCharged && inReach) {
                if (!select(me, spear)) return decide("swap");
                Vec3 aim = aimPoint(me, target);
                look(aim);
                // 8 degrees is wider than a player hitbox at 3 blocks, so that gate clicked air.
                if (!spearRayHits(me)) return decide("spear_aim");
                hit(me);
                return decide(meFalling ? "spear_fall" : targetFalling ? "spear_air" : "spear");
            }
"""
assert text.count(old) == 1, "block2"
text = text.replace(old, new, 1)
old = """        if (spear && (er < SPEAR_MIN || er > SPEAR_JAB_HI)) return; // dead zone or outer miss range
        if (!aimedAt(me, aim, spear ? 8f : 10f)) return; // must be looking at the target
        if (hit(me, target)) { attacks++; return; }
        double reach = spear ? SPEAR_JAB_HI : REACH;
        if (er <= reach && aimedAt(me, target.getEyePosition(), spear ? 8f : 10f)) {
            press(ctx.minecraft().options.keyAttack);
            attacks++;
        }
"""
new = """        if (spear) {
            // Piercing jab raycasts along the look vector. A click that is merely near the eyes misses
            // and, below full charge, is rejected. Do not press attack unless this ray connects.
            if (!spearRayHits(me)) return;
            press(ctx.minecraft().options.keyAttack);
            attacks++;
            return;
        }
        if (!aimedAt(me, aim, 10f)) return; // must be looking at the target
        if (hit(me, target)) { attacks++; return; }
        if (er <= REACH && aimedAt(me, target.getEyePosition(), 10f)) {
            press(ctx.minecraft().options.keyAttack);
            attacks++;
        }
"""
assert text.count(old) == 1, "block3"
text = text.replace(old, new, 1)
old = """    private static double exactReach(Player me, Entity t) {
"""
new = """    /** Horizontal distance from the eye to the target hitbox. Ignores a mace hop's vertical gap. */
    private static double horizontalBoxDist(Player me, Entity t) {
        Vec3 eye = me.getEyePosition();
        AABB b = t.getBoundingBox();
        double cx = Mth.clamp(eye.x, b.minX, b.maxX);
        double cz = Mth.clamp(eye.z, b.minZ, b.maxZ);
        return Math.hypot(eye.x - cx, eye.z - cz);
    }

    /**
     * The held spear's piercing ray hits the target inside the jab band, and the jab is fully charged.
     * Vanilla rejects a spear attack below minimum_attack_charge (1.0) and misses anything the ray misses.
     */
    private boolean spearRayHits(Player me) {
        ItemStack st = me.getMainHandItem();
        if (!isSpear(st) || me.cannotAttackWithItem(st, 0)) return false;
        net.minecraft.world.item.component.AttackRange range = me.entityAttackRange();
        net.minecraft.world.phys.HitResult hit = range.getClosesetHit(me, 1.0f, e -> e == target);
        if (!(hit instanceof net.minecraft.world.phys.EntityHitResult er) || er.getEntity() != target) return false;
        double along = me.getEyePosition().distanceTo(er.getLocation());
        return along >= SPEAR_MIN && along <= SPEAR_JAB_HI;
    }

    private static double exactReach(Player me, Entity t) {
"""
assert text.count(old) == 1, "block4"
text = text.replace(old, new, 1)
p.write_text(text, encoding="utf-8", newline="\n")
print("patched", len(text))
