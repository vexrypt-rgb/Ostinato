from pathlib import Path
p = Path(r"C:\Users\redfa\Documents\MinecraftDev\Ostinato-combat-1.21.11\src\main\java\baritone\process\VexBench.java")
t = p.read_text(encoding="utf-8")
old = """    private boolean loggedSwing;
"""
new = """    private boolean loggedSwing;
    private boolean worldAsked;
"""
assert t.count(old)==1
t = t.replace(old, new, 1)
old = """        if (done || event.getType() != TickEvent.Type.IN) return;
        LocalPlayer me = Minecraft.getInstance().player;
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (me == null || server == null) return;
"""
new = """        if (done || event.getType() != TickEvent.Type.IN) return;
        LocalPlayer me = Minecraft.getInstance().player;
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (me == null || server == null) {
            // The bench save is vexflat. Do not create a world and do not pass --quickPlay.
            if (!worldAsked && server == null && Minecraft.getInstance().screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
                worldAsked = true;
                log("opening vexflat");
                Minecraft.getInstance().createWorldOpenFlows().openWorld("vexflat", () -> {});
            }
            return;
        }
"""
assert t.count(old)==1, "tick"
t = t.replace(old, new, 1)
p.write_text(t, encoding="utf-8", newline="\n")
print("ok")
