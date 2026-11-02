package baritone.bombard;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/** Server mechanics oracle in a newly created disposable world. This does not certify client input execution. */
public final class BombardLiveTest implements AbstractGameEventListener {
    private final Baritone baritone;
    private int state, wait;
    private volatile boolean done;
    private volatile boolean ready, spearMoved;
    private final boolean rodFixture = Boolean.getBoolean("ostinato.bombardtest.rod");
    private volatile boolean rodAttached, rodMoved;
    private volatile float finalHealth = 20;
    private volatile int targetId;
    private PrimedTnt combatTnt; // server thread only
    private BombardLiveTest(Baritone baritone) { this.baritone = baritone; }
    public static void install(Baritone b) {
        if (Boolean.getBoolean("ostinato.bombardtest")) b.getGameEventHandler().registerEventListener(new BombardLiveTest(b));
    }
    private static void log(String text) {
        System.out.println("BOMBARDTEST " + text);
        try {
            java.nio.file.Files.writeString(Minecraft.getInstance().gameDirectory.toPath().resolve("bombardtest-result.txt"),
                    "BOMBARDTEST " + text + System.lineSeparator(), java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
    }
    @Override public void onTick(TickEvent event) {
        if (event.getState() != EventState.PRE) return;
        Minecraft mc = Minecraft.getInstance();
        if (state == 0 && mc.gui.screen() instanceof TitleScreen) {
            state = 1; wait = 40;
            mc.options.pauseOnLostFocus = false;
            CreateWorldScreen.openFresh(mc, () -> {});
        } else if (state == 1 && --wait <= 0 && mc.gui.screen() instanceof CreateWorldScreen screen) {
            screen.getUiState().setSeed("71007");
            for (var child : screen.children()) if (child instanceof Button button
                    && button.getMessage().getString().contains("Create New World")) {
                state = 2;
                button.onPress(new net.minecraft.client.input.InputWithModifiers() {
                    public int input() { return 0; }
                    public int modifiers() { return 0; }
                });
                break;
            }
        } else if (state == 2 && mc.player != null && mc.getSingleplayerServer() != null) {
            state = 3;
            var id = mc.player.getUUID();
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                try {
                    ServerPlayer player = server.getPlayerList().getPlayer(id);
                    run(player);
                    log("RESULT PASS server mechanics only; client cast, reach, packet timing and combat policy NOT validated");
                } catch (Throwable ex) { log("RESULT FAIL " + ex); ex.printStackTrace(); }
                finally { done = true; }
            });
        } else if (state == 3 && done) {
            state = 4;
            if (Boolean.getBoolean("ostinato.bombardtest.combat")) {
                ready = false;
                var server = mc.getSingleplayerServer();
                var id = mc.player.getUUID();
                server.execute(() -> {
                    var p = server.getPlayerList().getPlayer(id);
                    var level = p.level();
                    for (int x = -3; x <= 4; x++) for (int z = -3; z <= 30; z++)
                        level.setBlock(new BlockPos(x, 249, z), Blocks.OBSIDIAN.defaultBlockState(), 3);
                    if (rodFixture) for (int x = -8; x <= 8; x++) for (int y = 250; y <= 251; y++)
                        level.setBlock(new BlockPos(x, y, 2), Blocks.OBSIDIAN.defaultBlockState(), 3);
                    p.teleportTo(0.5, 250, 0.5);
                    p.setDeltaMovement(Vec3.ZERO);
                    p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED).setBaseValue(0);
                    p.setHealth(20);
                    p.getFoodData().setFoodLevel(20);
                    p.getInventory().clearContent();
                    var spear = new ItemStack(Items.DIAMOND_SPEAR);
                    spear.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.KNOCKBACK), 2);
                    p.setItemSlot(EquipmentSlot.MAINHAND, rodFixture ? new ItemStack(Items.FISHING_ROD) : spear);
                    p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.NETHERITE_HELMET));
                    p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.NETHERITE_CHESTPLATE));
                    p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.NETHERITE_LEGGINGS));
                    p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.NETHERITE_BOOTS));
                    var zombie = new net.minecraft.world.entity.monster.zombie.Zombie(level);
                    zombie.setNoAi(true);
                    zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.NETHERITE_HELMET));
                    // Within the existing spear controller's walking radius, and within blast reach of z~17.7.
                    double[] zp = rodFixture ? java.util.Arrays.stream(System.getProperty("ostinato.bombardtest.rod.zombie", "0.5,3.5").split(",")).mapToDouble(Double::parseDouble).toArray() : new double[]{0.5, 13.5}; // Ostinato: rod fixture zombie x,z knob
                    zombie.setPos(zp[0], 250, zp[1]);
                    level.addFreshEntity(zombie);
                    targetId = zombie.getId();
                    ready = true;
                });
                return;
            }
            if (Boolean.getBoolean("ostinato.bombardtest.exit")) mc.stop();
        } else if (state == 4 && ready && mc.level.getEntity(targetId) != null && mc.gui.screen() == null
                && mc.player.getMainHandItem().is(rodFixture ? Items.FISHING_ROD : Items.DIAMOND_SPEAR)
                && mc.player.getAttackStrengthScale(0) >= 1) {
            ((baritone.process.PvpProcess)baritone.getPvpProcess()).attack(e -> e.getId() == targetId, "bombard-disposable-" + (rodFixture ? "rod" : "spear"));
            baritone.getPvpProcess().setBombardPermit(new baritone.api.bombard.BombardPermit(true, 1, true,
                    rodFixture, !rodFixture, false, true, 4, "disposable-oracle"));
            wait = 30; state = 5;
        } else if (state == 5 && --wait <= 0) {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                combatTnt = new PrimedTnt(server.overworld(), 0.5, rodFixture ? Double.parseDouble(System.getProperty("ostinato.bombardtest.rod.tntY", "254")) : 250, rodFixture ? 4.5 : 3.5, null); // Ostinato: rod fixture TNT height knob
                combatTnt.setDeltaMovement(Vec3.ZERO);
                combatTnt.setFuse(rodFixture ? 35 : 40);
                server.overworld().addFreshEntity(combatTnt);
            });
            state = 6; wait = 80;
        } else if (state == 6) {
            var id = mc.player.getUUID();
            mc.getSingleplayerServer().execute(() -> {
                if (combatTnt != null && combatTnt.isAlive() && combatTnt.getDeltaMovement().z > 0.8) spearMoved = true;
                if (combatTnt != null && combatTnt.isAlive() && combatTnt.getDeltaMovement().z < -0.1) rodMoved = true;
                var player = mc.getSingleplayerServer().getPlayerList().getPlayer(id);
                if (player != null) {
                    finalHealth = player.getHealth();
                    if (player.fishing != null && player.fishing.getHookedIn() == combatTnt) rodAttached = true;
                    if (Boolean.getBoolean("ostinato.bombardtest.trace") && combatTnt != null) { // Ostinato: opt-in rod diagnosis trace (server side)
                        var h = player.fishing;
                        System.out.println(String.format(java.util.Locale.ROOT, "BOMBARDTRACE fuse=%d tnt=%.3f,%.3f,%.3f rot=%.1f/%.1f hook=%s", combatTnt.getFuse(), combatTnt.getX(), combatTnt.getY(), combatTnt.getZ(), player.getYRot(), player.getXRot(), h == null ? "-" : String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f v=%.3f,%.3f,%.3f ground=%s hooked=%s age=%d", h.getX(), h.getY(), h.getZ(), h.getDeltaMovement().x, h.getDeltaMovement().y, h.getDeltaMovement().z, h.onGround(), h.getHookedIn() == null ? "-" : h.getHookedIn().getType().toShortString(), h.tickCount)));
                    }
                }
            });
            if (--wait <= 0) {
                baritone.getPvpProcess().onLostControl();
                if (rodFixture) log("CLIENT_ROD " + (rodAttached && rodMoved ? "PASS" : "FAIL")
                        + " serverAttachment=" + rodAttached + " serverReelImpulse=" + rodMoved + " finalHealth=" + finalHealth);
                else log("CLIENT_SPEAR " + (spearMoved ? "PASS" : "FAIL")
                        + " server observed forward TNT impulse; stationary player/target fixture only; finalHealth=" + finalHealth);
                state = 7;
                if (Boolean.getBoolean("ostinato.bombardtest.exit")) mc.stop();
            }
        }
    }
    private static void run(ServerPlayer player) throws Exception {
        var level = player.level();
        player.setPos(0.5, 250, 0.5);
        long start = System.nanoTime();
        double maximum = 0;
        for (int kb = 0; kb <= 2; kb++) for (int yaw : new int[]{0, 30, 90, 180, 270}) {
            var spear = new ItemStack(Items.DIAMOND_SPEAR);
            if (kb > 0) spear.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.KNOCKBACK), kb);
            player.setItemSlot(EquipmentSlot.MAINHAND, spear);
            player.setYRot(yaw);
            PrimedTnt tnt = new PrimedTnt(level, 0.5, 250, 3.5, null);
            tnt.setDeltaMovement(Vec3.ZERO);
            player.stabAttack(EquipmentSlot.MAINHAND, tnt, 1, true, true, false);
            double[] expected = TntMechanics.spearPush(yaw, kb);
            double error = tnt.getDeltaMovement().distanceTo(new Vec3(expected[0], expected[1], expected[2]));
            maximum = Math.max(maximum, error);
            if (error > 0.0002) throw new AssertionError("spear yaw=" + yaw + " kb=" + kb + " error=" + error);
            tnt.discard();
        }
        log("spear PASS 15 cases kb=0..2 yaw=0/30/90/180/270 maxVelocityError=" + maximum);
        var rod = new ItemStack(Items.FISHING_ROD);
        player.setItemSlot(EquipmentSlot.MAINHAND, rod);
        PrimedTnt tnt = new PrimedTnt(level, 3.5, 250, 0.5, null);
        tnt.setDeltaMovement(Vec3.ZERO);
        level.addFreshEntity(tnt); // synced hooked-entity id resolves through the world's entity lookup
        FishingHook hook = new FishingHook(player, level, 0, 0);
        var hit = FishingHook.class.getDeclaredMethod("onHitEntity", EntityHitResult.class);
        hit.setAccessible(true);
        hit.invoke(hook, new EntityHitResult(tnt));
        if (hook.getHookedIn() != tnt) throw new AssertionError("hook handler did not attach TNT");
        hook.setPos(tnt.getX(), tnt.getY(0.8), tnt.getZ());
        int durability = hook.retrieve(rod);
        double[] pull = TntMechanics.rodPull(tnt.getX(), tnt.getY(), tnt.getZ(), player.getX(), player.getY(), player.getZ());
        double error = tnt.getDeltaMovement().distanceTo(new Vec3(pull[0], pull[1], pull[2]));
        if (durability != 5 || error > 1e-7) throw new AssertionError("rod durability=" + durability + " error=" + error);
        tnt.discard();
        log("rod PASS server hit-handler attachment + retrieve durability=5 velocityError=" + error + " (cast collision not exercised)");
        BlockPos secondary = new BlockPos(20, 250, 0);
        level.setBlock(secondary, Blocks.TNT.defaultBlockState(), 3);
        new ServerExplosion(level, null, null, null, Vec3.atCenterOf(secondary), 4, false, Explosion.BlockInteraction.DESTROY).explode();
        var primed = level.getEntitiesOfClass(PrimedTnt.class, new AABB(secondary).inflate(3));
        if (primed.isEmpty()) throw new AssertionError("explosion did not prime secondary TNT");
        for (PrimedTnt chain : primed) {
            if (chain.getFuse() < 10 || chain.getFuse() > 29) throw new AssertionError("chain fuse=" + chain.getFuse());
            chain.discard();
        }
        int minimum = 80, max = 0;
        for (int i = 0; i < 1000; i++) {
            int fuse = PrimedTnt.getRandomShortFuse(80, level.getRandom());
            minimum = Math.min(minimum, fuse); max = Math.max(max, fuse);
            if (fuse < 10 || fuse > 29) throw new AssertionError("random fuse=" + fuse);
        }
        log("chain PASS actual explosion primed=" + primed.size() + " randomFuseSamples=1000 min=" + minimum + " max=" + max);
        log("oracle elapsedMs=" + (System.nanoTime() - start) / 1e6);
    }
}
