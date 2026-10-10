import pathlib
p = pathlib.Path(r"src/main/java/baritone/process/PvpProcess.java")
t = p.read_text(encoding="utf-8")
old = """        if (spearSlot(me) >= 0 && macePhase == 0 && pearlStage == 0) {
            PathingCommand tool = spearTools(me, dist, los, wind, mace);
            if (tool != null) return tool;
            return null;
        }"""
new = """        if (spearSlot(me) >= 0 && macePhase == 0 && pearlStage == 0) {
            // Their mace dive is the ~9 damage in the spear logs. Shield it; do not hop into it.
            if (shieldDive(me, dist)) return decide("block");
            PathingCommand tool = spearTools(me, dist, los, wind, mace);
            if (tool != null) return tool;
            return null;
        }"""
if old not in t:
    raise SystemExit("missing spear early return")
t = t.replace(old, new, 1)
old = """            if (macePhase == 2) { // flying: steer to the target, smash while falling
                maceTicks++;
                if (!select(me, mace)) return decide("swap");"""
new = """            if (macePhase == 2) { // flying: steer to the target, smash while falling
                maceTicks++;
                // Abort a hop that is not a smash when their dive is the one that will land.
                if (spearKit && me.fallDistance < 1.2 && shieldDive(me, dist)) {
                    macePhase = 0;
                    maceCool = 8;
                    return decide("block");
                }
                if (!select(me, mace)) return decide("swap");"""
if old not in t:
    raise SystemExit("missing phase2")
t = t.replace(old, new, 1)
old = """        if (mace >= 0 && wind >= 0 && maceCool == 0 && me.onGround() && !me.isInWater() && los
                && hr > SPEAR_JAB_HI && hr <= 5.2 && target.getY() <= me.getY() + 1.5) {"""
new = """        if (mace >= 0 && wind >= 0 && maceCool == 0 && me.onGround() && !me.isInWater() && los
                && target.onGround() && hr > SPEAR_JAB_HI && hr <= 5.2 && target.getY() <= me.getY() + 1.5) {"""
if old not in t:
    raise SystemExit("missing hop start")
t = t.replace(old, new, 1)
old = """            throwStraightDown(me);
            windCool = 30;
            return decide("wind");"""
new = """            throwStraightDown(me);
            windCool = 30;
            // The burst launches us. Fall onto them; do not spear-hold in the air under a mace.
            macePhase = 2;
            maceTicks = 0;
            return decide("wind");"""
if old not in t:
    raise SystemExit("missing deadzone throw")
t = t.replace(old, new, 1)
old = """    private boolean shouldBlock(Player me, double dist) {"""
new = """    /** Shield a mace dive. True when the shield is being raised this tick. */
    private boolean shieldDive(Player me, double dist) {
        if (dist > 7 || target.onGround() || target.getY() < me.getY() + 1.0) return false;
        if (tv().y > 0.2 && target.getY() < me.getY() + 2.2) return false;
        if (me.getOffhandItem().getItem() != Items.SHIELD && slotOf(me, Items.SHIELD) < 0) return false;
        if (me.getOffhandItem().getItem() != Items.SHIELD) toOffhand(me, Items.SHIELD);
        int spear = spearSlot(me);
        if (spear >= 0) select(me, spear);
        look(target.getEyePosition());
        use(true);
        if (blockTicks++ == 0) blocks++;
        return true;
    }

    private boolean shouldBlock(Player me, double dist) {"""
if old not in t:
    raise SystemExit("missing shouldBlock")
t = t.replace(old, new, 1)
p.write_text(t, encoding="utf-8", newline="\n")
print("patched")
