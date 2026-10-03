import pathlib
root = pathlib.Path(r"src/main/java/baritone/process")

p = root / "PvpProcess.java"
t = p.read_text(encoding="utf-8")
old = """        float hp = me.getHealth() + me.getAbsorptionAmount();
        if (lastHealth >= 0 && hp < lastHealth) damageTaken += lastHealth - hp;
        lastHealth = hp;
"""
new = """        float hp = me.getHealth() + me.getAbsorptionAmount();
        if (lastHealth >= 0 && hp < lastHealth) damageTaken += lastHealth - hp;
        boolean respawned = lastHealth >= 0 && lastHealth < 5 && hp > lastHealth + 8;
        lastHealth = hp;
        // Bench respawn drops the kit before VexBench's item replace lands. Do not swing naked.
        if (Integer.getInteger("ostinato.vexbench", 0) > 0 && (respawned
                || me.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty())) {
            if (recorder.active()) recorder.end(me, respawned ? "death" : "lost");
            use(false);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
"""
if old not in t:
    raise SystemExit("missing hp track")
t = t.replace(old, new, 1)
old = """            if (spear >= 0 && spearBand == 0 && los && spearCharged && inReach) {
                if (!select(me, spear)) return decide("swap");
                Vec3 aim = aimPoint(me, target);
                look(aim);
                // 8 degrees is wider than a player hitbox at 3 blocks, so that gate clicked air.
                if (!spearRayHits(me)) return decide("spear_aim");
                hit(me);
"""
new = """            double slide = Math.hypot(me.getDeltaMovement().x, me.getDeltaMovement().z);
            // The 04:36 miss was a full-charge jab while still sliding backward. Wait until stopped.
            if (spear >= 0 && spearBand == 0 && los && spearCharged && inReach && slide < 0.03 && target.hurtTime <= 0
                    && me.getAttackStrengthScale(0f) >= 0.99f) {
                if (!select(me, spear)) return decide("swap");
                Vec3 aim = aimPoint(me, target);
                look(aim);
                // 8 degrees is wider than a player hitbox at 3 blocks, so that gate clicked air.
                if (!spearRayHits(me)) return decide("spear_aim");
                hit(me);
"""
if old not in t:
    raise SystemExit("missing jab")
t = t.replace(old, new, 1)
old = """                } else if (me.fallDistance > 1.5 && exactReach(me, target) <= REACH - 0.05
                        && (me.fallDistance >= 3 || !ctx.world().noCollision(me, me.getBoundingBox().move(0, -1.3, 0)))) {
                    hit(me);
"""
new = """                } else if (me.fallDistance > 1.5 && me.getDeltaMovement().y < -0.05
                        && me.getAttackStrengthScale(0f) >= 0.99f && target.hurtTime <= 0
                        && exactReach(me, target) <= REACH - 0.05
                        && (me.fallDistance >= 3 || !ctx.world().noCollision(me, me.getBoundingBox().move(0, -1.3, 0)))) {
                    hit(me);
"""
if old not in t:
    raise SystemExit("missing smash")
t = t.replace(old, new, 1)
old = """        return along >= SPEAR_MIN && along <= SPEAR_JAB_HI;
"""
new = """        return along >= SPEAR_JAB_LO && along <= SPEAR_JAB_HI;
"""
if old not in t:
    raise SystemExit("missing ray")
t = t.replace(old, new, 1)
p.write_text(t, encoding="utf-8", newline="\n")

v = root / "VexBench.java"
t = v.read_text(encoding="utf-8")
old = """                "gamerule immediate_respawn true", "gamerule advance_time false",
"""
new = """                "gamerule immediate_respawn true", "gamerule keep_inventory true", "gamerule advance_time false",
"""
if old not in t:
    raise SystemExit("missing gamerules")
t = t.replace(old, new, 1)
old = """        if (event.getType() != TickEvent.Type.IN) return;
        if (me.isDeadOrDying() && wait > 0) { // round over: respawn before the next one starts
"""
new = """        if (event.getType() != TickEvent.Type.IN) return;
        reequip(me);
        if (me.isDeadOrDying() && wait > 0) { // round over: respawn before the next one starts
"""
if old not in t:
    raise SystemExit("missing ontick")
t = t.replace(old, new, 1)
old = """    private void gear(String name) {
        if (!RANDOM) {
            for (String k : KIT) run("item replace entity " + name + " " + k);
            return;
        }
"""
new = """    private float lastLifeHp = -1;

    /** Full kit after every respawn, even with keep_inventory. A naked tick must not start a fight. */
    private void reequip(LocalPlayer me) {
        if (me == null || me.isDeadOrDying()) return;
        float hp = me.getHealth();
        boolean snapped = lastLifeHp >= 0 && lastLifeHp < 5 && hp > lastLifeHp + 8;
        boolean naked = me.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty();
        lastLifeHp = hp;
        if (!snapped && !naked) return;
        log(snapped ? "respawn reequip" : "naked reequip");
        gear(me.getGameProfile().name());
    }

    private void gear(String name) {
        if (!RANDOM) {
            for (String k : KIT) run("item replace entity " + name + " " + k);
            MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
            if (server != null) server.submit(() -> {
                ServerPlayer sp = findPlayer(server, name);
                if (sp != null) sp.getInventory().selected = 0; // hold the spear / first kit slot
            }).join();
            return;
        }
"""
if old not in t:
    raise SystemExit("missing gear")
t = t.replace(old, new, 1)
v.write_text(t, encoding="utf-8", newline="\n")
print("ok")
