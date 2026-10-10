from pathlib import Path
p = Path(r"C:\Users\redfa\Documents\MinecraftDev\Ostinato-combat-1.21.11\src\main\java\baritone\process\VexBench.java")
t = p.read_text(encoding="utf-8")
old = """        if (done || event.getType() != TickEvent.Type.IN) return;
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
new = """        if (done) return;
        LocalPlayer me = Minecraft.getInstance().player;
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        // Title-screen ticks are OUT (no player yet). Open the existing vexflat save once.
        if (me == null || server == null) {
            if (!worldAsked && server == null && Minecraft.getInstance().screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
                worldAsked = true;
                log("opening vexflat");
                Minecraft.getInstance().createWorldOpenFlows().openWorld("vexflat", () -> {});
            }
            return;
        }
        if (event.getType() != TickEvent.Type.IN) return;
"""
assert t.count(old)==1, "missing"
t = t.replace(old, new, 1)
p.write_text(t, encoding="utf-8", newline="\n")
print("ok")
