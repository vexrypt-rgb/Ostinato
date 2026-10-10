from pathlib import Path
p = Path(r"C:\Users\redfa\Documents\MinecraftDev\Ostinato-combat-1.21.11\src\launch\java\baritone\launch\mixins\MixinMinecraft.java")
t = p.read_text(encoding="utf-8")
old = """    @Unique
    private BiFunction<EventState, TickEvent.Type, TickEvent> tickProvider;
"""
new = """    @Unique
    private BiFunction<EventState, TickEvent.Type, TickEvent> tickProvider;

    @Unique
    private boolean ostinato$openedVexflat;

    @Unique
    private int ostinato$menuTicks;

    /** Title-screen ticks do not reach VexBench. Open the existing vexflat save, never a new world. */
    @Inject(method = "tick", at = @At("HEAD"))
    private void ostinato$openBenchWorld(CallbackInfo ci) {
        if (Integer.getInteger("ostinato.vexbench", 0) <= 0 || this.ostinato$openedVexflat) return;
        Minecraft mc = (Minecraft) (Object) this;
        if (this.player != null || mc.getSingleplayerServer() != null) return;
        if (!(mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen)) {
            if (mc.screen != null && this.ostinato$menuTicks++ % 100 == 0)
                System.out.println("VEXBENCH screen " + mc.screen.getClass().getName());
            return;
        }
        this.ostinato$openedVexflat = true;
        System.out.println("VEXBENCH opening vexflat");
        mc.createWorldOpenFlows().openWorld("vexflat", () -> {});
    }
"""
assert t.count(old)==1
t = t.replace(old, new, 1)
p.write_text(t, encoding="utf-8", newline="\n")
print("mixin ok")
