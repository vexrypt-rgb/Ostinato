from pathlib import Path

p = Path(r"C:\Users\redfa\Documents\MinecraftDev\Ostinato-combat-1.21.11\src\main\java\baritone\process\PvpProcess.java")
text = p.read_text(encoding="utf-8")

old = """        // Spear kit jabs. Wind charges and shield-holding were keeping it outside the 2-4 band.
        // Lunge is decided in onTick and only fires when the spear has Lunge I-III.
        if (spearSlot(me) >= 0 && macePhase == 0 && pearlStage == 0) return null;
"""
new = """        // Spear kit still jabs. Wind is only a knock-in just outside the band, or the hop under a mace smash.
        // Far wind-charge spam and shield-holding stay off. A plain spear never lunges.
        if (spearSlot(me) >= 0 && macePhase == 0 && pearlStage == 0) {
            PathingCommand tool = spearTools(me, dist, los, wind, mace);
            if (tool != null) return tool;
            return null;
        }
"""
assert text.count(old) == 1, "special early"
text = text.replace(old, new, 1)

old = """        if (mace >= 0) {
            // Spear kit: stay grounded and jab; wind-jumping is gap-closing, not a spear attack.
            if (spearSlot(me) >= 0 && macePhase == 0) return null;
            boolean canJump = me.onGround() && !me.isInWater();
"""
new = """        if (mace >= 0) {
            boolean spearKit = spearSlot(me) >= 0;
            boolean canJump = me.onGround() && !me.isInWater();
            // Spear kit starts the smash from spearTools, and only from just outside the jab.
"""
assert text.count(old) == 1, "mace start"
text = text.replace(old, new, 1)

old = """            if (macePhase == 0 && canJump && maceCool == 0 && !overhead && dist > 2.5 && dist < 24 && los && wind >= 0) {
"""
new = """            if (!spearKit && macePhase == 0 && canJump && maceCool == 0 && !overhead && dist > 2.5 && dist < 24 && los && wind >= 0) {
"""
assert text.count(old) == 1, "mace cond"
text = text.replace(old, new, 1)

old = """                return decide("lunge");
            }
            if (wind < 0 || maceCool > 0 || dist <= 3) {
"""
new = """                return decide("mace");
            }
            if (wind < 0 || maceCool > 0 || dist <= 3) {
"""
assert text.count(old) == 1, "mace return"
text = text.replace(old, new, 1)

old = """        boolean spear = spearSlot(me) >= 0;
"""
new = """        boolean spear = isSpear(me.getMainHandItem());
"""
assert text.count(old) == 1, "hit spear"
text = text.replace(old, new, 1)

old = """    /** Horizontal distance from the eye to the target hitbox. Ignores a mace hop's vertical gap. */
"""
new = """    /**
     * Spear kit, and only when a jab is not available.
     * One wind charge aimed past the target knocks them into the 2.6-3.4 band.
     * A wind charge at our feet is only the launch for a mace smash onto a target just outside that band.
     */
    private PathingCommand spearTools(Player me, double dist, boolean los, int wind, int mace) {
        double hr = horizontalBoxDist(me, target);
        if (mace >= 0 && wind >= 0 && maceCool == 0 && me.onGround() && !me.isInWater() && los
                && hr > SPEAR_JAB_HI && hr <= 5.2 && target.getY() <= me.getY() + 1.5) {
            if (!select(me, wind)) return decide("swap");
            key(Input.JUMP);
            macePhase = 1;
            maceTicks = 0;
            return decide("mace");
        }
        if (wind >= 0 && windCool == 0 && los && me.onGround() && hr > SPEAR_JAB_HI && hr <= 6.5
                && Math.abs(target.getY() - me.getY()) < 2.5) {
            Vec3 away = target.position().subtract(me.position());
            double len = Math.hypot(away.x, away.z);
            if (len > 0.1) {
                Vec3 at = target.position().add(away.x / len * 1.4, 0.4, away.z / len * 1.4);
                look(at);
                if (!select(me, wind)) return decide("swap");
                if (!aimedAt(me, at, 10f)) return decide("wind");
                press(ctx.minecraft().options.keyUse);
                windCool = 30;
                return decide("wind");
            }
        }
        return null;
    }

    /** Horizontal distance from the eye to the target hitbox. Ignores a mace hop's vertical gap. */
"""
assert text.count(old) == 1, "tools insert"
text = text.replace(old, new, 1)
p.write_text(text, encoding="utf-8", newline="\n")
print("pvp ok")

v = Path(r"C:\Users\redfa\Documents\MinecraftDev\Ostinato-combat-1.21.11\src\main\java\baritone\process\VexBench.java")
vt = v.read_text(encoding="utf-8")
old = """                    run("gamerule doMobSpawning false", "gamerule doImmediateRespawn true", "gamerule doDaylightCycle false",
                            "time set day", "difficulty normal", "gamemode survival @a", "kill @e[type=!player]");
"""
new = """                    // 1.21.11 renamed doMobSpawning; the old command is rejected and hostiles keep spawning.
                    clearHostiles();
                    run("time set day", "difficulty normal", "gamemode survival @a");
"""
assert vt.count(old) == 1, "bench init"
vt = vt.replace(old, new, 1)
old = """        run("kill @e[type=!player]", "kill @a[name=!" + meName + "]", "kill @e[type=item]",
"""
new = """        clearHostiles();
        run("kill @a[name=!" + meName + "]", "kill @e[type=item]",
"""
assert vt.count(old) == 1, "bench next"
vt = vt.replace(old, new, 1)
old = """    private void setup() {
        setup = false;
"""
new = """    /** Hostiles off for every round. Leaves players, items, and armor stands. Not peaceful. */
    private void clearHostiles() {
        run("gamerule spawn_mobs false", "gamerule spawn_monsters false", "gamerule spawn_phantoms false",
                "gamerule spawn_patrols false", "gamerule spawn_wardens false",
                "gamerule immediate_respawn true", "gamerule advance_time false",
                "difficulty normal",
                "kill @e[type=!player,type=!item,type=!armor_stand]",
                "gamerule spawn_monsters");
    }

    private void setup() {
        setup = false;
        clearHostiles();
"""
assert vt.count(old) == 1, "bench setup"
vt = vt.replace(old, new, 1)
v.write_text(vt, encoding="utf-8", newline="\n")
print("bench ok")
