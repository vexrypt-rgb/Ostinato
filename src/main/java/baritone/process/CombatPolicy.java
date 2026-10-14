package baritone.process;

import java.util.List;

/**
 * The tunable numbers behind combat decisions, as plain fields. Each one has a default (what the fights taught), a
 * hard min and max, and can be overridden for a run with {@code -Dostinato.policy.<group>.<name>=value} so a sweep
 * needs no rebuild. Values are fixed for the life of the run, so a seeded bench stays deterministic. The comment
 * beside a number says what it measures; the fight logs it came from stay in the comments at the use site.
 * A number that should follow the opponent (their swing gap, say) is read from {@link PvpOpponent} instead.
 * <p>
 * The exceptions are the {@link Tuned} numbers, listed in {@link #tuned}: {@code #pvp tune} can move one by hand
 * between fights so two values can be compared in one session, and with {@code pvpAutotune} on {@link CombatTuner}
 * moves them, one number and one step at a time and only between two of {@link CombatOutcome}'s windows. Left alone
 * each stays at its baseline. The ones in {@link #whole} are worth what a whole fight shows and not what ten seconds
 * of one do: those {@link CombatFightTuner} moves, and only between two fights.
 */
final class CombatPolicy {
    final Spear spear = new Spear();
    final Shield shield = new Shield();
    final Mace mace = new Mace();
    final Melee melee = new Melee();
    final Pots pots = new Pots();
    final Mend mend = new Mend();
    final Buckets buckets = new Buckets();
    final Totems totems = new Totems();
    final Pearls pearls = new Pearls();
    final Heal heal = new Heal();
    /** Every number that can move while running, in the order the autotune takes them up. */
    final List<Tuned> tuned = List.of(shield.holdCharge, shield.raiseCharge, shield.dueLate, shield.dueEarly, shield.holdMaxTicks, shield.dueDist,
            mace.smashCharge, mace.landingCharge, mace.hitCool, mace.missCool,
            melee.duelCharge, melee.duelSpace, melee.spaceCharge, melee.critCharge, melee.jumpCharge,
            melee.spaceAny, melee.shieldWait, melee.hitSelect, mace.smashCrit);
    /**
     * The numbers a fight is fought whole under, in the order {@link CombatFightTuner} takes them up: when to heal and
     * when a totem comes out. What one of them is worth shows in how a fight ends, not in the damage of any ten
     * seconds of it, so they are kept apart from {@link #tuned} and are not part of {@link #setting}.
     */
    final List<Tuned> whole = List.of(heal.eatNear, heal.eatFar, heal.eatSafe, heal.keep, pots.healAt, totems.wantAt, totems.handAt, pearls.keep);
    private final int[] named = new int[tuned.size()];
    private String setting;

    /**
     * The values in force, as {@link CombatOutcome} keeps its windows apart by them: {@code base} with every number
     * at its baseline, otherwise the ones that are not, as {@code shield.dueLate:6,shield.holdCharge:86}.
     */
    String setting() {
        boolean same = setting != null;
        for (int i = 0; i < named.length; i++) {
            int v = tuned.get(i).get();
            if (v != named[i]) same = false;
            named[i] = v;
        }
        if (!same) {
            StringBuilder sb = new StringBuilder();
            for (Tuned t : tuned) {
                if (t.get() != t.base) sb.append(sb.length() > 0 ? "," : "").append(t.name).append(':').append(t.get());
            }
            setting = sb.length() > 0 ? sb.toString() : BASE;
        }
        return setting;
    }

    /** The name of the setting with every tuned number at its baseline. */
    static final String BASE = "base";

    /** The same for the numbers of {@link #whole}, as {@link CombatOutcome} names a fight by them. */
    String stance() {
        StringBuilder sb = null;
        for (Tuned t : whole) {
            if (t.get() == t.base) continue;
            if (sb == null) sb = new StringBuilder();
            sb.append(sb.length() > 0 ? "," : "").append(t.name).append(':').append(t.get());
        }
        return sb == null ? BASE : sb.toString();
    }

    /** The tuned number of that name, of either list, null if there is none. */
    Tuned tuned(String name) {
        for (Tuned t : tuned) {
            if (t.name.equals(name)) return t;
        }
        for (Tuned t : whole) {
            if (t.name.equals(name)) return t;
        }
        return null;
    }

    /**
     * Golden apples: the health at or under which a bite is started. PROVISIONAL — PENDING IN-GAME CALIBRATION. The
     * three baselines are the lines the fights against VexBot were fought with before they could move; none has been
     * weighed against a neighbour. A point is half a heart, and a step is one heart.
     */
    static final class Heal {
        /** With the foe inside six blocks. Two critical sword hits take about 9. */
        final Tuned eatNear = Tuned.whole("heal.eatNear", "apple", 9, 5, 15, 2);
        /** With the foe farther off than that, where a bite is cheap. */
        final Tuned eatFar = Tuned.whole("heal.eatFar", "apple", 12, 8, 18, 2);
        /** With the foe out of reach and not swinging, or in the gap after the swing of a slow weapon. */
        final Tuned eatSafe = Tuned.whole("heal.eatSafe", "apple", 11, 7, 17, 2);
        /**
         * Apples kept back: with this many or fewer left, only the near line starts a bite, and the far and the safe
         * one wait. 0 keeps none back, which is how every fight so far was fought.
         */
        final Tuned keep = Tuned.whole("heal.keep", "apple", 0, 0, 4, 1);
    }

    /** Potions: when one that heals is thrown, how far the foe must be for a boost, and from where one that harms is thrown. PROVISIONAL — PENDING IN-GAME CALIBRATION. */
    static final class Pots {
        /** Health at or under which a healing potion is thrown. One heals 8 at its second level, and two crits of a sharp sword take about 9. */
        final Tuned healAt = Tuned.whole("pots.healAt", "heal", 9, 5, 15, 2);
        /** Ticks between two throws at our own feet: the first has to break and the health to come back before a second is judged. */
        final int gap = (int) param("pots.gap", 10, 4, 40);
        /** A boost is thrown only with the foe farther than this: a splash reaches 4 blocks and the foe closes while it falls. */
        final double boostDist = param("pots.boostDist", 5.5, 4, 16);
        /** A boost with fewer ticks than this left counts as run out. */
        final int renewTicks = (int) param("pots.renewTicks", 40, 0, 400);
        /** What harms is thrown at the foe between these distances, and no sooner than harmGap ticks after the last throw. */
        final double harmLo = param("pots.harmLo", 3, 2, 6);
        final double harmHi = param("pots.harmHi", 12, 6, 20);
        final int harmGap = (int) param("pots.harmGap", 25, 5, 100);
    }

    /** Mending with bottles o' enchanting: how worn a piece must be, how much room the foe must give, and what a piece about to break is worth. PROVISIONAL — PENDING IN-GAME CALIBRATION. */
    static final class Mend {
        /** Share of durability left on the most worn piece at which bottles come out, and the share at which they are put away. */
        final double startAt = param("mend.startAt", 0.15, 0.02, 0.6);
        final double stopAt = param("mend.stopAt", 0.5, 0.1, 1.0);
        /** The foe must be farther than room to begin (or be eating), and mending stops once it is inside close. */
        final double room = param("mend.room", 7, 4, 20);
        final double close = param("mend.close", 4.5, 3, 10);
        /** Ticks between two bottles. */
        final int gap = (int) param("mend.gap", 2, 1, 10);
        /** At this share or under, ground is given to mend, for at most leaveTicks at a time and not again within leaveRest. */
        final double urgentAt = param("mend.urgentAt", 0.07, 0, 0.3);
        final int leaveTicks = (int) param("mend.leaveTicks", 100, 0, 400);
        final int leaveRest = (int) param("mend.leaveRest", 600, 100, 6000);
    }

    /** Buckets: when water is worth its ticks, where lava is poured from, and how long a poured source is left out. PROVISIONAL — PENDING IN-GAME CALIBRATION. */
    static final class Buckets {
        /** Blocks fallen at which water is put under us. A fall hurts by its length less three, so six costs three points. */
        final double minFall = param("buckets.minFall", 6, 4, 30);
        /** Ticks between two uses of a bucket: the first has to show in the world before a second is judged. */
        final int gap = (int) param("buckets.gap", 4, 2, 20);
        /** Ticks our water is left out before it is taken back if its work is not seen done sooner. */
        final int waterHold = (int) param("buckets.waterHold", 6, 2, 40);
        /** Lava is poured under a foe between these distances: inside the bucket's reach, outside where its first flow comes. */
        final double lavaLo = param("buckets.lavaLo", 2.2, 1.5, 4);
        final double lavaHi = param("buckets.lavaHi", 3.6, 2.5, 4.5);
        /** Ticks our lava is left under a foe that has not caught fire yet. */
        final int lavaHold = (int) param("buckets.lavaHold", 10, 2, 60);
        /** Ticks between two pours of lava. A foe on fire burns for fifteen seconds unless it finds water. */
        final int lavaGap = (int) param("buckets.lavaGap", 100, 20, 1200);
        /** Ticks the view is given to come to rest on the foe's cell, the weapon still in hand; the bucket comes out only once it has. */
        final int lavaAim = (int) param("buckets.lavaAim", 6, 1, 20);
        /** Ticks before lava is tried again after a try that ended without a pour. */
        final int lavaRetry = (int) param("buckets.lavaRetry", 40, 5, 400);
        /** A source that could not be taken back within these ticks, or that is farther than far, is left where it is. */
        final int limit = (int) param("buckets.limit", 60, 20, 400);
        final double far = param("buckets.far", 6, 4.5, 16);
        /** We stay this far from our own lava while it is out. */
        final double keepOff = param("buckets.keepOff", 1.8, 1, 4);
    }

    /** Totems: when a spare one is held in the hand while the offhand waits for its own. PROVISIONAL — PENDING IN-GAME CALIBRATION. */
    static final class Totems {
        /** Health at or under which a totem is wanted in the offhand in place of the shield. */
        final Tuned wantAt = Tuned.whole("totems.wantAt", "totem", 8, 4, 14, 2);
        /** Health at or under which the next hit may be the last, so a totem goes into the hand at once. A blast near us does the same at any health. */
        final Tuned handAt = Tuned.whole("totems.handAt", "totem", 4, 0, 8, 2);
    }

    /** Pearls: when one is thrown to catch a foe that running does not. PROVISIONAL — PENDING IN-GAME CALIBRATION. */
    static final class Pearls {
        /** A chase pearl is thrown at a foe between these distances. Nearer, running gets there first; farther, the throw is a guess. */
        final double chaseLo = param("pearls.chaseLo", 10, 6, 30);
        final double chaseHi = param("pearls.chaseHi", 45, 15, 60);
        /** Blocks a second we must be gaining for the chase to count as working. A sprint is 5.6. */
        final double chaseGain = param("pearls.chaseGain", 1.0, 0, 5);
        /** Seconds in a row without that gain before the pearl goes. */
        final int chaseAfter = (int) param("pearls.chaseAfter", 2, 1, 20);
        /** Health needed for it: the landing costs five. */
        final double chaseHp = param("pearls.chaseHp", 12, 6, 20);
        /** The throw is taken only when it lands within this of where the foe will be. */
        final double chaseErr = param("pearls.chaseErr", 3, 1, 8);
        /** Ticks before another chase pearl. */
        final int chaseRest = (int) param("pearls.chaseRest", 200, 20, 2400);
        /** Ticks the turn to the throw is given, the run held meanwhile; a throw that has not gone by then is tried again after chaseRetry. */
        final int chaseAim = (int) param("pearls.chaseAim", 12, 2, 40);
        final int chaseRetry = (int) param("pearls.chaseRetry", 40, 5, 400);
        /**
         * Pearls kept back for the escape: with this many or fewer left, none goes on a chase, a lift or a strike.
         * 0 keeps none back, which is how every fight so far was fought.
         */
        final Tuned keep = Tuned.whole("pearls.keep", "pearl", 0, 0, 4, 1);
    }

    /** Spear: when to jab, when to start a charge, and the window in which a released charge pierces. */
    static final class Spear {
        /** Horizontal speed under which a jab is taken; faster slides miss. */
        final double jabSlide = param("spear.jabSlide", 0.03, 0.0, 0.1);
        /** Inside this distance with a jab not ready, wait up to jabWaitTicks and then back out. */
        final double jabBand = param("spear.jabBand", 4.8, 3.5, 6.0);
        final int jabWaitTicks = (int) param("spear.jabWaitTicks", 30, 5, 80);
        /** A committed back-out ends after this many ticks, or once closer than commitHr. */
        final int commitTicks = (int) param("spear.commitTicks", 36, 10, 100);
        final double commitHr = param("spear.commitHr", 3.2, 2.0, 4.5);
        /** Give up reopening range after this many ticks. */
        final int reopenTicks = (int) param("spear.reopenTicks", 70, 20, 200);
        /** Distance at which the back-out turns to face the target. */
        final double faceHr = param("spear.faceHr", 7.4, 5.0, 10.0);
        /** Aim tolerance in degrees before a run-up, and the looser one after faceSlowTicks. */
        final float faceTol = (float) param("spear.faceTol", 25, 10, 45);
        final float faceTolLate = (float) param("spear.faceTolLate", 50, 25, 90);
        /** Kinetic speed along the line (blocks/s) needed for a charge to pierce, and the floor under which it is abandoned. */
        final double chargeAlong = param("spear.chargeAlong", 4.6, 3.0, 6.0);
        final double failAlong = param("spear.failAlong", 4.2, 2.5, 5.5);
        /** Predicted distance at tick 10 must fall in this window to start a charge. */
        final double at10Lo = param("spear.at10Lo", 2.4, 1.5, 3.5);
        final double at10Hi = param("spear.at10Hi", 4.4, 3.0, 5.0);
        /** Charge may start between startHrMin and startHrMax. */
        final double startHrMin = param("spear.startHrMin", 4.55, 3.5, 6.0);
        final double startHrMax = param("spear.startHrMax", 14, 8, 20);
        /** Earliest release tick, and the distance window in which the release pierces. */
        final int minUseTicks = (int) param("spear.minUseTicks", 11, 8, 20);
        final double pierceLo = param("spear.pierceLo", 2.05, 1.5, 3.0);
        final double pierceHi = param("spear.pierceHi", 4.5, 3.5, 5.5);
        /** A charge is given up beyond this distance or after this many ticks. */
        final double failHr = param("spear.failHr", 16, 10, 24);
        final int maxUseTicks = (int) param("spear.maxUseTicks", 200, 50, 400);
        /** Ticks to wait after a charge before another. */
        final int useCool = (int) param("spear.useCool", 8, 0, 40);
    }

    /** Shield: when to raise it against a sword and how long to hold it. */
    static final class Shield {
        /** Raised and holding: lower once our own swing charge is back above this (or the foe's swing is not due). */
        final Tuned holdCharge = new Tuned("shield.holdCharge", "shield", 78, 50, 95, 8, 100);
        /** Lowered: raise once our swing charge is under this (or the foe's swing is due). */
        final Tuned raiseCharge = new Tuned("shield.raiseCharge", "shield", 45, 20, 70, 10, 100);
        /** A raise is held at most this many ticks. */
        final Tuned holdMaxTicks = new Tuned("shield.holdMaxTicks", "shield", 40, 10, 100, 10, 1);
        /** Their swing gap assumed until one is observed (a sword recharges in 12.5 ticks). */
        final int defaultGap = (int) param("shield.defaultGap", 13, 8, 25);
        /** A swing counts as due from gap - dueEarly to gap + dueLate ticks after the last, inside dueDist blocks. */
        final Tuned dueEarly = new Tuned("shield.dueEarly", "shield", 6, 0, 12, 3, 1);
        final Tuned dueLate = new Tuned("shield.dueLate", "shield", 10, 2, 20, 4, 1);
        final Tuned dueDist = new Tuned("shield.dueDist", "shield", 45, 30, 60, 5, 10);
    }

    /** Mace: the swing charge a smash needs and the cooldowns after a hop. */
    /**
     * The sword and axe exchange. The first three are read only against a foe who duels with sword and shield, which
     * a mob never does; the last two only against one who does not.
     */
    static final class Melee {
        /**
         * Charge at which a swing from the ground goes in a duel: higher waits for a harder hit. It is never tried
         * under 0.9: the game gives the sprint knockback, like the crit, only to a swing charged above that.
         */
        final Tuned duelCharge = new Tuned("melee.duelCharge", "/player", 90, 90, 100, 5, 100);
        /** In a duel, while the sword is under {@code spaceCharge}, the distance inside which we step back out of the foe's reach. */
        final Tuned duelSpace = new Tuned("melee.duelSpace", "/player", 29, 25, 33, 2, 10);
        final Tuned spaceCharge = new Tuned("melee.spaceCharge", "/player", 75, 55, 95, 10, 100);
        /** Charge at which the falling swing of a jump is taken as a crit. Under 0.9 the game does not make it one. */
        final Tuned critCharge = new Tuned("melee.critCharge", null, 90, 90, 100, 5, 100);
        /** Least charge at which we jump for a crit; a slow weapon waits for more whatever this says. */
        final Tuned jumpCharge = new Tuned("melee.jumpCharge", null, 55, 45, 75, 10, 100);
        /**
         * Three ways of fighting that are off at the baseline and are the autotune's to try. PROVISIONAL — PENDING IN-GAME
         * CALIBRATION: none has been fought yet, which is why none is switched on by hand.
         * <p>
         * spaceAny: step back out of reach while the weapon is under spaceCharge against any armed player, not only in a
         * sword-and-shield duel (0 no, 1 yes).
         */
        final Tuned spaceAny = new Tuned("melee.spaceAny", "/player", 0, 0, 1, 1, 1);
        /** Ticks a charged swing is kept for a raised shield to come down when the kit has no axe to break it with. 0 swings into it. */
        final Tuned shieldWait = new Tuned("melee.shieldWait", "/player", "axe", 0, 0, 30, 10, 1);
        /**
         * Ticks a charged swing from the ground is kept while the foe's own swing is due, so that it lands first: a hit
         * that knocks back takes the attacker out of its sprint and slows it, and ours then meets it standing. 0 never waits.
         */
        final Tuned hitSelect = new Tuned("melee.hitSelect", "/player", 0, 0, 4, 2, 1);
    }

    static final class Mace {
        /** Charge needed to swing during a dive, and the lower one when landing or passing the target. */
        final Tuned smashCharge = new Tuned("mace.smashCharge", "mace", "spear", 60, 30, 99, 10, 100);
        final Tuned landingCharge = new Tuned("mace.landingCharge", "mace", "spear", 40, 20, 90, 10, 100);
        /** Ticks to wait after a smash and after a hop that missed. With a spear in the kit the dive has its own charge and its own wait after a miss. */
        final Tuned hitCool = new Tuned("mace.hitCool", "mace", null, 14, 0, 60, 4, 1);
        final Tuned missCool = new Tuned("mace.missCool", "mace", "spear", 20, 0, 100, 5, 1);
        /**
         * The dive's swing as a critical hit (0 no, 1 yes): the game multiplies the whole smash, fall bonus included, by
         * 1.5 when the charge is over 0.9 and the attacker is not sprinting (Player.attack and canCriticalAttack, 1.21.11).
         * At 1 the dive waits for that charge and comes out of its sprint critLead ticks before the fall arrives; the
         * swing on the last tick is made whatever the charge, as before. PROVISIONAL — PENDING IN-GAME CALIBRATION: not
         * fought yet, so it is the autotune's to try and not switched on by hand.
         */
        final Tuned smashCrit = new Tuned("mace.smashCrit", "mace", "spear", 0, 0, 1, 1, 1);
        final int critLead = (int) param("mace.critLead", 6, 2, 20);
        /** Wind-charge flick: fall time (ticks) window and the combined charge that makes it worth it. */
        final int flickLo = (int) param("mace.flickLo", 14, 8, 20);
        final int flickHi = (int) param("mace.flickHi", 26, 20, 40);
        final float flickCharge = (float) param("mace.flickCharge", 0.84, 0.5, 1.0);
    }

    /**
     * A number that can be moved while running, held as a whole number of {@code 1/per}: a charge of 0.78 is 78 with
     * {@code per} 100, a count of ticks is itself with {@code per} 1. It starts at its baseline (the default, or the
     * -D override, which is given in the number's own unit like any other), {@link #set} refuses a value outside the
     * hard min and max and leaves the old one in place, and {@link #reset} puts the baseline back. {@code step} is
     * how far apart the values worth telling apart lie: {@link CombatTuner} weighs the trusted value against a
     * neighbour one step away and never a value in between. {@code kit} names what a kit must hold for the number
     * to matter at all (as {@code PvpProcess.kit} writes it), or the one kind of foe it matters against (as
     * {@code /player}), null when it always does; {@code not} names what a
     * kit must not hold, because with it the number is not read, null when there is no such thing. A number that is
     * {@code whole} is one a fight is fought whole under; its {@code kit} may also name what is carried to stay alive
     * with ({@code apple}, {@code heal} for a potion that heals, {@code totem}).
     */
    static final class Tuned {
        final String name, kit, not;
        final int base, min, max, step, per;
        final boolean whole;
        private volatile int value;

        Tuned(String name, int def, int min, int max, int step) {
            this(name, null, def, min, max, step, 1);
        }

        Tuned(String name, String kit, int def, int min, int max, int step, int per) {
            this(name, kit, null, def, min, max, step, per);
        }

        Tuned(String name, String kit, String not, int def, int min, int max, int step, int per) {
            this(name, kit, not, def, min, max, step, per, false);
        }

        /** A number a fight is fought whole under, in points of health: {@link CombatFightTuner}'s to move, and only between two fights. */
        static Tuned whole(String name, String kit, int def, int min, int max, int step) {
            return new Tuned(name, kit, null, def, min, max, step, 1, true);
        }

        private Tuned(String name, String kit, String not, int def, int min, int max, int step, int per, boolean whole) {
            this.whole = whole;
            this.name = name;
            this.kit = kit;
            this.not = not;
            this.min = min;
            this.max = max;
            this.step = step;
            this.per = per;
            base = value = (int) Math.round(param(name, def / (double) per, min / (double) per, max / (double) per) * per);
        }

        int get() {
            return value;
        }

        /** The value in the number's own unit. */
        double real() {
            return value / (double) per;
        }

        /** A value of this number in its own unit, as text: 10, 0.86, 4.5. */
        String show(int v) {
            return per == 1 ? Integer.toString(v) : per == 10 ? String.format(java.util.Locale.ROOT, "%.1f", v / 10.0)
                    : String.format(java.util.Locale.ROOT, "%.2f", v / (double) per);
        }

        boolean set(int v) {
            if (v < min || v > max) return false;
            value = v;
            return true;
        }

        void reset() {
            value = base;
        }
    }

    private static double param(String name, double def, double min, double max) {
        String v = System.getProperty("ostinato.policy." + name);
        if (v == null) return def;
        try {
            return Math.max(min, Math.min(max, Double.parseDouble(v)));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
