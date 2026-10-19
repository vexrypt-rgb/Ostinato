/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.spectate;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.event.events.PacketEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.utils.input.Input;
import baritone.behavior.Behavior;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

/**
 * Ostinato: watch-only server spectating. Joins one server, answers its /register or /login prompt, walks its lobby
 * menus (a per-server route file first, then menu words), gets to a spectator spot, and with {@code spectateLearn} on
 * records the fights it sees. It never attacks and never chats: the only things it ever sends are /register, /login,
 * the route's own commands and menu clicks, all rate limited. It leaves at once, and never comes back, on a captcha, a
 * staff message about bots, a kick or a ban, and ends the session after {@code autoSpectateMinutes}.
 */
public final class ServerSpectate extends Behavior implements baritone.api.utils.Helper {

    public enum Phase { IDLE, CONNECTING, AUTH, ROUTE, WATCH, LEAVING }

    private static final Pattern COLOR = Pattern.compile("§.");
    private static final Pattern CAPTCHA = Pattern.compile("captcha|verify you are human|are you a robot|type the code|enter the code");
    private static final Pattern BOT_WORD = Pattern.compile("\\b(bots?|botting|macro(s|ing)?|anti-?bot|automation|afk ?check)\\b");
    private static final Pattern STAFF = Pattern.compile("\\b(staff|mod|moderator|admin|helper|owner|manager|sr\\.? ?mod|developer|dev)\\b");
    private static final Pattern BAN = Pattern.compile("\\b(you (are|have been|were) (banned|kicked|blacklisted)|banned from|ban(ned)? reason|kicked from|you got kicked)\\b");
    private static final Pattern REGISTER = Pattern.compile("/(register|reg)\\b");
    private static final Pattern LOGIN = Pattern.compile("/(login|l)\\b");
    private static final Pattern AUTH_OK = Pattern.compile("successfully (registered|logged)|logged in|login successful|registered successfully|welcome back|you are now (logged|registered)");
    private static final Pattern WRONG_PW = Pattern.compile("wrong password|incorrect password|invalid password");
    private static final Pattern ALREADY = Pattern.compile("already registered|is registered|please login|use /login");
    /** Never clicked: these would queue us into a fight or buy something. */
    private static final Pattern NEVER = Pattern.compile("\\b(ranked|unranked|queue|join queue|1v1 me|duel request|send duel|buy|purchase|store|shop|confirm purchase|kit editor|edit kit|party)\\b");
    private static final String[] DEFAULT_WORDS = {"spectate", "spectator", "spec", "watch", "ffa", "arena", "practice", "duel"};
    private static final Pattern SELECTOR = Pattern.compile("selector|navigator|menu|games|game menu|server|compass|play|practice|ffa|spectate|modes");

    private final ConcurrentLinkedQueue<String[]> inbox = new ConcurrentLinkedQueue<>(); // {kind, text}, network thread to game
    private final Random rnd = new Random();
    private Phase phase = Phase.IDLE;
    private String host;
    private long startMs, nextActionMs, lastAuthMs;
    private int commandsSent, clicks, hotbarUses, registerTries, loginTries;
    private boolean authed, sawAuthPrompt, spectateTried;
    private final List<String[]> route = new ArrayList<>();
    private int routeStep;
    private String[] words = DEFAULT_WORDS;
    private final Set<String> menusSeen = new LinkedHashSet<>();
    private final Map<Integer, Long> hurtAt = new HashMap<>(); // entity id -> ms last seen hurt
    private int target = -1;
    private long targetSince;
    private int resultsAtStart;
    private String endReason = "";
    private final List<String> sessionLog = new ArrayList<>();

    public ServerSpectate(Baritone baritone) {
        super(baritone);
    }

    private static Settings s() {
        return BaritoneAPI.getSettings();
    }

    private static Path dir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("baritone").resolve("servers");
    }

    // ------------------------------------------------------------------ blocked servers

    public static boolean blocked(String host) {
        return blockedReason(host) != null;
    }

    public static String blockedReason(String host) {
        Path f = dir().resolve("blocked.txt");
        if (!Files.exists(f)) return null;
        try {
            for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] p = l.split("\t", 3);
                if (p.length >= 1 && p[0].trim().equalsIgnoreCase(host)) return p.length >= 3 ? p[2] : "blocked";
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private void block(String reason) {
        try {
            Files.createDirectories(dir());
            Files.writeString(dir().resolve("blocked.txt"), host + "\t" + LocalDateTime.now() + "\t" + reason.replace('\n', ' ') + "\n",
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ password (local only, never shown)

    private String password() {
        Path f = dir().resolve(host + ".secret");
        try {
            if (Files.exists(f)) return Files.readString(f, StandardCharsets.UTF_8).trim();
            Files.createDirectories(dir());
            String abc = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
            SecureRandom r = new SecureRandom();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(abc.charAt(r.nextInt(abc.length())));
            Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (Exception ignored) {
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ route file

    /** Route lines: {@code cmd /x}, {@code hotbar word}, {@code click word}, {@code wait seconds}, {@code words a,b,c}. */
    static List<String[]> parseRoute(List<String> lines, List<String> wordsOut) {
        List<String[]> out = new ArrayList<>();
        for (String raw : lines) {
            String l = raw.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            String[] p = l.split("\\s+", 2);
            String k = p[0].toLowerCase(Locale.ROOT), v = p.length > 1 ? p[1].trim() : "";
            if (k.equals("words")) {
                for (String w : v.split(",")) if (!w.isBlank()) wordsOut.add(w.trim().toLowerCase(Locale.ROOT));
            } else if (k.equals("cmd") || k.equals("hotbar") || k.equals("click") || k.equals("wait")) {
                out.add(new String[]{k, v});
            }
        }
        return out;
    }

    private void loadRoute() {
        route.clear();
        routeStep = 0;
        words = DEFAULT_WORDS;
        List<String> lines = null;
        Path f = dir().resolve(host + ".txt");
        try {
            if (Files.exists(f)) {
                lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            } else {
                try (var in = ServerSpectate.class.getResourceAsStream("/assets/baritone/ostinato/servers/" + host + ".txt")) {
                    if (in != null) lines = Arrays.asList(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"));
                }
            }
        } catch (IOException ignored) {
        }
        if (lines == null) return;
        List<String> w = new ArrayList<>();
        route.addAll(parseRoute(lines, w));
        if (!w.isEmpty()) words = w.toArray(new String[0]);
    }

    // ------------------------------------------------------------------ control

    public Phase phase() {
        return phase;
    }

    public boolean running() {
        return phase != Phase.IDLE;
    }

    public void start(String h) {
        if (running()) {
            logDirect("Auto-spectate is already running (" + host + ")");
            return;
        }
        host = h.trim().toLowerCase(Locale.ROOT);
        String why = blockedReason(host);
        if (why != null) {
            logDirect("Auto-spectate: " + host + " is blocked here (" + why + "), not joining again");
            return;
        }
        s().spectateLearn.value = true;
        s().spectateLearnOnServers.value = true;
        s().spectateSaveLogs.value = true;
        loadRoute();
        startMs = System.currentTimeMillis();
        nextActionMs = startMs + 3000;
        commandsSent = clicks = hotbarUses = registerTries = loginTries = 0;
        authed = sawAuthPrompt = spectateTried = false;
        menusSeen.clear();
        hurtAt.clear();
        sessionLog.clear();
        inbox.clear();
        target = -1;
        endReason = "";
        resultsAtStart = baritone.getSpectateLearner().results().size();
        note("start " + host + " route steps " + route.size() + " words " + String.join(",", words));
        Minecraft mc = Minecraft.getInstance();
        ServerData cur = mc.getCurrentServer();
        if (mc.level != null && cur != null && (cur.ip.equalsIgnoreCase(host) || cur.name.equalsIgnoreCase(host))) {
            phase = Phase.AUTH;
        } else {
            phase = Phase.CONNECTING;
            mc.execute(() -> {
                if (mc.level != null) { mc.level.disconnect(); mc.disconnect(); } // Ostinato: 1.21.4 disconnect
                // Ostinato: -Dostinato.autospectate.addr=ip:port dials that address instead (a box whose DNS hides the real one)
                String addr = System.getProperty("ostinato.autospectate.addr", host);
                ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(addr), new ServerData(host, addr, ServerData.Type.OTHER), false, null);
            });
        }
        logDirect("Auto-spectate: joining " + host + " to watch only (" + s().autoSpectateMinutes.value + " min cap)");
    }

    public void stop(String why) {
        if (!running()) return;
        endReason = why;
        note("stop: " + why);
        baritone.getInputOverrideHandler().clearAllKeys();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.getCurrentServer() != null) {
            if (mc.level != null) mc.level.disconnect(); // Ostinato: 1.21.4 disconnect ("auto-spectate ended: " + why)
            mc.disconnect();
        }
        phase = Phase.IDLE;
        flushLog();
        logDirect("Auto-spectate stopped: " + why + ". " + summary());
    }

    private void leaveAndBlock(String why) {
        note("SAFETY " + why);
        block(why);
        stop("left and blocked: " + why);
    }

    public String summary() {
        List<SpectateLearner.GroupResult> r = baritone.getSpectateLearner().results();
        int n = Math.max(0, r.size() - resultsAtStart);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%s phase %s, %ds, %d commands, %d clicks, %d fights recorded",
                host, phase, (System.currentTimeMillis() - startMs) / 1000, commandsSent, clicks, n));
        for (int i = Math.max(resultsAtStart, r.size() - 8); i < r.size(); i++) sb.append("\n  ").append(r.get(i));
        if (!endReason.isEmpty()) sb.append("\n  ended: ").append(endReason);
        return sb.toString();
    }

    public List<String> menus() {
        return new ArrayList<>(menusSeen);
    }

    private void note(String s) {
        String line = LocalDateTime.now().withNano(0) + " " + s;
        sessionLog.add(line);
        System.out.println("[OSTSPEC] " + line);
    }

    private void flushLog() {
        if (host == null) return;
        try {
            Files.createDirectories(dir());
            Files.write(dir().resolve("session-" + host + ".log"), sessionLog, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            sessionLog.clear();
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ network thread: just queue text

    @Override
    public void onReceivePacket(PacketEvent event) {
        if (phase == Phase.IDLE || event.getState() != EventState.PRE) return;
        Object p = event.getPacket();
        if (p instanceof ClientboundSystemChatPacket c) {
            inbox.add(new String[]{c.overlay() ? "bar" : "sys", c.content().getString()});
        } else if (p instanceof ClientboundPlayerChatPacket c) {
            String t = c.unsignedContent() != null ? c.unsignedContent().getString() : c.body().content(); // Ostinato: nullable on 1.21.11
            inbox.add(new String[]{"chat", t});
        } else if (p instanceof ClientboundSetTitleTextPacket c) {
            inbox.add(new String[]{"title", c.text().getString()});
        } else if (p instanceof ClientboundSetSubtitleTextPacket c) {
            inbox.add(new String[]{"title", c.text().getString()});
        } else if (p instanceof ClientboundDisconnectPacket c) {
            inbox.add(new String[]{"kick", c.reason().getString()});
        }
    }

    // ------------------------------------------------------------------ game thread

    // Ostinato: headless test hook: -Dostinato.autospectate=<host> starts one session from the title screen,
    // -Dostinato.autospectate.exit=true closes the game when it ends.
    private static final String AUTO = System.getProperty("ostinato.autospectate");
    private static final boolean AUTO_EXIT = Boolean.getBoolean("ostinato.autospectate.exit");
    private boolean autoFired;
    private long autoSeen, idleSince;

    private void autoHook(Minecraft mc, long now) {
        if (AUTO == null || AUTO.isBlank()) return;
        if (!autoFired) {
            if (mc.screen instanceof TitleScreen) {
                if (autoSeen == 0) autoSeen = now;
                if (now - autoSeen > 5000) {
                    autoFired = true;
                    start(AUTO);
                    if (phase == Phase.IDLE && AUTO_EXIT) mc.stop();
                }
            }
            return;
        }
        if (phase == Phase.IDLE && AUTO_EXIT) {
            if (idleSince == 0) idleSince = now;
            if (now - idleSince > 4000) {
                note("exit: " + summary());
                flushLog();
                mc.stop();
            }
        }
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getState() != EventState.PRE) return;
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        autoHook(mc, now);
        if (phase == Phase.IDLE) return;
        for (String[] m; (m = inbox.poll()) != null; ) {
            if (handleText(m[0], m[1], now)) return;
        }
        if (phase == Phase.IDLE) return;
        if (now - startMs > s().autoSpectateMinutes.value * 60_000L) {
            stop("session cap " + s().autoSpectateMinutes.value + " min");
            return;
        }
        if (mc.screen instanceof DisconnectedScreen ds) {
            String why = disconnectReason(ds);
            note("disconnected: " + why);
            phase = Phase.IDLE;
            // a kick, ban or refused login: never retry this server (a plain network failure is not a kick)
            if (!why.toLowerCase(Locale.ROOT).matches(".*(connection refused|timed out|unknown host|cannot resolve|no further information|connection reset|network is unreachable).*")) {
                block("disconnected: " + why);
            }
            endReason = "disconnected: " + why;
            flushLog();
            logDirect("Auto-spectate: disconnected from " + host + " (" + why + "); marked blocked. " + summary());
            return;
        }
        if (event.getType() == TickEvent.Type.OUT || mc.player == null) {
            if (phase != Phase.CONNECTING && phase != Phase.LEAVING && now - startMs > 5000 && !(mc.screen instanceof ConnectScreen)) {
                // back at a menu without a kick screen: treat as ended
                phase = Phase.IDLE;
                endReason = "left the server";
                flushLog();
            }
            return;
        }
        if (phase == Phase.CONNECTING) {
            phase = Phase.AUTH;
            lastAuthMs = now;
            note("joined world, gamemode " + mc.gameMode.getPlayerMode() + " at " + mc.player.blockPosition());
        }
        if (mc.screen instanceof AbstractContainerScreen<?> cs && !(cs.getMenu() instanceof net.minecraft.world.inventory.InventoryMenu)) {
            menu(cs, now);
            return;
        }
        switch (phase) {
            case AUTH -> {
                // no prompt within 12 s: the server needs no login (or uses a premium session)
                if (!sawAuthPrompt && now - lastAuthMs > 12_000) {
                    authed = true;
                    note("no auth prompt seen; going on");
                }
                if (authed) {
                    phase = Phase.ROUTE;
                    nextActionMs = now + 2500 + rnd.nextInt(2500);
                }
            }
            case ROUTE -> route(mc, now);
            case WATCH -> watch(mc, now);
            default -> {
            }
        }
    }

    private static String disconnectReason(DisconnectedScreen ds) {
        try { // Ostinato: 1.21.11 reads it through a mixin accessor (intermediary names at runtime)
            Component r = ((baritone.utils.accessor.IOstinatoScreens) ds).ostinato$reason();
            if (r != null) return r.getString();
            return ds.getTitle().getString();
        } catch (Throwable t) {
            return ds.getTitle().getString();
        }
    }

    /** Returns true when the session ended. */
    private boolean handleText(String kind, String raw, long now) {
        String text = COLOR.matcher(raw).replaceAll("").trim();
        if (text.isEmpty()) return false;
        String pw = host == null ? null : peekPassword();
        String shown = pw != null && !pw.isEmpty() ? text.replace(pw, "<pw>") : text;
        note(kind + ": " + shown);
        String l = text.toLowerCase(Locale.ROOT);
        if (kind.equals("kick")) {
            leaveAndBlock("kicked: " + shown);
            return true;
        }
        if (CAPTCHA.matcher(l).find()) {
            leaveAndBlock("captcha: " + shown);
            return true;
        }
        boolean botWord = BOT_WORD.matcher(l).find();
        if (botWord && (kind.equals("sys") || kind.equals("title") || STAFF.matcher(l).find()
                || (Minecraft.getInstance().player != null && l.contains(Minecraft.getInstance().player.getName().getString().toLowerCase(Locale.ROOT))))) {
            leaveAndBlock("staff/bot message: " + shown);
            return true;
        }
        if (!kind.equals("chat") && BAN.matcher(l).find()) {
            leaveAndBlock("ban/kick message: " + shown);
            return true;
        }
        if (kind.equals("chat")) return false; // other players talking: never answered
        if (AUTH_OK.matcher(l).find()) {
            authed = true;
            return false;
        }
        if (WRONG_PW.matcher(l).find()) {
            stop("wrong password");
            return true;
        }
        if (!authed && phase.ordinal() <= Phase.AUTH.ordinal() + 1) {
            boolean reg = REGISTER.matcher(l).find(), log = LOGIN.matcher(l).find();
            if (reg || log) sawAuthPrompt = true;
            if (now - lastAuthMs < 4000) return false; // let prompts repeat without answering each
            String p = password();
            if (p == null) return false;
            if (log && (!reg || ALREADY.matcher(l).find() || registerTries > 0) && loginTries < 2) {
                loginTries++;
                lastAuthMs = now;
                sendLater("login " + p, "login", now);
            } else if (reg && registerTries < 2) {
                registerTries++;
                lastAuthMs = now;
                sendLater("register " + p + " " + p, "register", now);
            }
        }
        return false;
    }

    private String cachedPw;

    private String peekPassword() {
        if (cachedPw == null && Files.exists(dir().resolve(host + ".secret"))) cachedPw = password();
        return cachedPw;
    }

    private final List<Object[]> pending = new ArrayList<>(); // {dueMs, command, shownAs}

    /**
     * Queues a command, typed into chat key by key after a think pause, at most one every 4 s. Shown in the log only
     * as {@code shown}.
     */
    private void sendLater(String cmd, String shown, long now) {
        long due = Math.max(now + HumanInput.between(rnd, s().autoSpectateThinkMinMs.value, s().autoSpectateThinkMaxMs.value), nextActionMs);
        nextActionMs = due + 4000;
        pending.add(new Object[]{due, cmd, shown});
    }

    // Ostinato: human typing state
    private String typing, typingShown;
    private long[] typeDelays;
    private int typeIdx;
    private long typeNext;

    private boolean busy() {
        return typing != null || path != null || hoverUntil != 0 || releaseAt != 0;
    }

    private void flushPending(Minecraft mc, long now) {
        if (typing != null) {
            type(mc, now);
            return;
        }
        if (busy() || mc.screen != null) return; // chat opens only over the world
        for (Iterator<Object[]> it = pending.iterator(); it.hasNext(); ) {
            Object[] p = it.next();
            if ((long) p[0] > now) continue;
            it.remove();
            if (commandsSent >= 10) {
                note("command cap reached; not sending " + p[2]);
                return;
            }
            typing = "/" + p[1];
            typingShown = (String) p[2];
            typeDelays = HumanInput.keyDelays(typing, rnd, s().autoSpectateKeyMinMs.value, s().autoSpectateKeyMaxMs.value);
            typeIdx = 0;
            // the chat key, then a beat before the first key
            mc.setScreen(new net.minecraft.client.gui.screens.ChatScreen(""));
            typeNext = now + HumanInput.between(rnd, 150, 400);
            note("typing /" + typingShown);
            return;
        }
    }

    /** One step of typing: each key at its own time, then enter, then the password is taken out of the chat history. */
    private void type(Minecraft mc, long now) {
        if (!(mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen)) {
            note("chat closed while typing /" + typingShown + "; dropped");
            typing = null;
            return;
        }
        long handle = mc.getWindow().getWindow();
        while (typeIdx < typing.length() && now >= typeNext) {
            ((baritone.utils.accessor.IOstinatoInput) mc.keyboardHandler).ostinato$charTyped(handle, typing.charAt(typeIdx), 0);
            typeNext += typeDelays[typeIdx];
            typeIdx++;
        }
        if (typeIdx < typing.length() || now < typeNext) return;
        int enter = com.mojang.blaze3d.platform.InputConstants.KEY_RETURN; // Ostinato: 1.21.4 takes raw GLFW key args
        ((baritone.utils.accessor.IOstinatoInput) mc.keyboardHandler).ostinato$keyPress(handle, enter, 28, 1, 0);
        ((baritone.utils.accessor.IOstinatoInput) mc.keyboardHandler).ostinato$keyPress(handle, enter, 28, 0, 0);
        commandsSent++;
        note("sent /" + typingShown);
        scrubHistory(mc);
        typing = null;
        if (mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen) mc.setScreen(null);
    }

    /** The typed /register and /login stay out of the chat history and command_history.txt. */
    private void scrubHistory(Minecraft mc) {
        String pw = peekPassword();
        if (pw == null || pw.isEmpty()) return;
        try {
            mc.gui.getChat().getRecentChat().removeIf(x -> x.contains(pw)); // Ostinato: 1.21.11 has the getter
        } catch (Throwable ignored) {
        }
        try {
            ((baritone.utils.accessor.IOstinatoScreens) mc.commandHistory()).ostinato$scrub(x -> x.contains(pw)); // Ostinato: mixin on 1.21.11
        } catch (Throwable t) {
            note("could not scrub command history: " + t);
        }
    }

    @Override
    public void onPostTick(TickEvent event) {
        if (phase == Phase.IDLE || event.getType() != TickEvent.Type.IN) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            long now = System.currentTimeMillis();
            flushPending(mc, now);
            cursor(mc, now);
        }
    }

    @Override
    public void onRenderPass(baritone.api.event.events.RenderEvent event) {
        if (phase == Phase.IDLE) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        long now = System.currentTimeMillis();
        if (typing != null) type(mc, now);
        cursor(mc, now); // smoother than ticks
    }

    // ------------------------------------------------------------------ human cursor

    private List<double[]> path;
    private long pathStart, hoverUntil, releaseAt;
    private int pathContainer = -1, lastMenuId = -1, pathSlot = -1;
    private String pathName;

    /** Window pixels of a slot's middle, a few gui pixels off centre. */
    private double[] slotPx(Minecraft mc, AbstractContainerScreen<?> cs, Slot sl) {
        int left = 0, top = 0;
        try { // Ostinato: mixin accessor on 1.21.11
            int[] lt = ((baritone.utils.accessor.IOstinatoScreens) cs).ostinato$pos();
            left = lt[0];
            top = lt[1];
        } catch (Throwable ignored) {
        }
        double gx = left + sl.x + 8 + (rnd.nextDouble() * 2 - 1) * 4, gy = top + sl.y + 8 + (rnd.nextDouble() * 2 - 1) * 4;
        var w = mc.getWindow();
        double sx = (double) w.getScreenWidth() / w.getGuiScaledWidth(), sy = (double) w.getScreenHeight() / w.getGuiScaledHeight();
        return new double[]{gx * sx, gy * sy};
    }

    private void startClick(Minecraft mc, AbstractContainerScreen<?> cs, Slot sl, String name, long now) {
        double[] to = slotPx(mc, cs, sl);
        path = HumanInput.cursorPath(mc.mouseHandler.xpos(), mc.mouseHandler.ypos(), to[0], to[1], rnd);
        pathStart = now;
        pathContainer = cs.getMenu().containerId;
        pathSlot = sl.index;
        pathName = name;
    }

    /** Moves the cursor along its path, rests on the slot, then presses and releases the left button. */
    private void cursor(Minecraft mc, long now) {
        long handle = mc.getWindow().getWindow();
        if (releaseAt != 0 && now >= releaseAt) {
            ((baritone.utils.accessor.IOstinatoInput) mc.mouseHandler).ostinato$onButton(handle, 0, 0, 0);
            releaseAt = 0;
            nextActionMs = now + HumanInput.between(rnd, s().autoSpectateClickGapMinMs.value, s().autoSpectateClickGapMaxMs.value);
        }
        if (path == null && hoverUntil == 0) return;
        if (!(mc.screen instanceof AbstractContainerScreen<?> cs) || cs.getMenu().containerId != pathContainer) {
            path = null; // the menu went away under the cursor
            hoverUntil = 0;
            return;
        }
        if (path != null) {
            long el = now - pathStart;
            double[] at = path.get(path.size() - 1);
            for (double[] p : path) if (p[0] >= el) {
                at = p;
                break;
            }
            double ox = mc.mouseHandler.xpos(), oy = mc.mouseHandler.ypos();
            ((baritone.utils.accessor.IOstinatoInput) mc.mouseHandler).ostinato$onMove(handle, at[1], at[2]); // Ostinato: 1.21.11 takes no deltas
            if (el >= path.get(path.size() - 1)[0]) {
                path = null;
                hoverUntil = now + HumanInput.between(rnd, s().autoSpectateHoverMinMs.value, s().autoSpectateHoverMaxMs.value);
            }
            return;
        }
        if (now < hoverUntil) return;
        hoverUntil = 0;
        clicks++;
        note("click \"" + pathName + "\" (slot " + pathSlot + ")");
        ((baritone.utils.accessor.IOstinatoInput) mc.mouseHandler).ostinato$onButton(handle, 0, 1, 0);
        releaseAt = now + HumanInput.between(rnd, 50, 110);
        nextActionMs = Long.MAX_VALUE; // set on release
    }

    private static void key(Minecraft mc, int key, int keycode) {
        long h = mc.getWindow().getWindow();
        ((baritone.utils.accessor.IOstinatoInput) mc.keyboardHandler).ostinato$keyPress(h, key, keycode, 1, 0);
        ((baritone.utils.accessor.IOstinatoInput) mc.keyboardHandler).ostinato$keyPress(h, key, keycode, 0, 0);
    }

    // ------------------------------------------------------------------ menus

    static int score(String name, String[] words) {
        String l = name.toLowerCase(Locale.ROOT);
        if (NEVER.matcher(l).find()) return -1;
        for (int i = 0; i < words.length; i++) if (l.contains(words[i])) return words.length - i;
        return 0;
    }

    private void menu(AbstractContainerScreen<?> cs, long now) {
        AbstractContainerMenu m = cs.getMenu();
        String title = COLOR.matcher(cs.getTitle().getString()).replaceAll("");
        List<String> names = new ArrayList<>();
        Slot best = null;
        int bestScore = 0;
        String want = null;
        if (phase == Phase.ROUTE && routeStep < route.size() && route.get(routeStep)[0].equals("click")) want = route.get(routeStep)[1].toLowerCase(Locale.ROOT);
        for (Slot sl : m.slots) {
            if (sl.container instanceof net.minecraft.world.entity.player.Inventory) continue;
            ItemStack st = sl.getItem();
            if (st.isEmpty()) continue;
            String n = COLOR.matcher(st.getHoverName().getString()).replaceAll("");
            names.add(n);
            int sc = want != null ? (n.toLowerCase(Locale.ROOT).contains(want) && !NEVER.matcher(n.toLowerCase(Locale.ROOT)).find() ? 100 : 0) : score(n, words);
            if (sc > bestScore) {
                bestScore = sc;
                best = sl;
            }
        }
        String key = title + " | " + String.join(", ", names);
        if (menusSeen.add(key)) note("menu \"" + title + "\": " + String.join(", ", names));
        if (m.containerId != lastMenuId) { // Ostinato: a person reads a menu before clicking in it
            lastMenuId = m.containerId;
            nextActionMs = Math.max(nextActionMs, now + HumanInput.between(rnd, s().autoSpectateReadMinMs.value, s().autoSpectateReadMaxMs.value));
        }
        if (busy() || now < nextActionMs) return;
        if (best == null || clicks >= 12 || phase == Phase.WATCH) {
            // nothing to do here (or already watching): close it like a person would
            nextActionMs = now + 2000 + rnd.nextInt(1500);
            if (now - menuOpenedMs(now) > 3000) key(Minecraft.getInstance(), com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE, 27); // Ostinato: Esc, like a person
            return;
        }
        String n = COLOR.matcher(best.getItem().getHoverName().getString()).replaceAll("");
        note("move to \"" + n + "\" in \"" + title + "\"");
        startClick(Minecraft.getInstance(), cs, best, n, now); // Ostinato: the cursor travels there, rests, then clicks
        if (want != null) routeStep++;
        if (n.toLowerCase(Locale.ROOT).matches(".*\\b(spectate|spectator|spec|watch)\\b.*")) spectateTried = true;
        menuSeenAt = 0;
    }

    private long menuSeenAt;

    private long menuOpenedMs(long now) {
        if (menuSeenAt == 0) menuSeenAt = now;
        return menuSeenAt;
    }

    // ------------------------------------------------------------------ route and fallback

    private void route(Minecraft mc, long now) {
        if (pendingUse != 0 && now >= pendingUse) {
            pendingUse = 0;
            net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.getKey(mc.options.keyUse.saveString()));
        }
        if (now < nextActionMs || !pending.isEmpty() || busy()) return;
        menuSeenAt = 0;
        if (routeStep < route.size()) {
            String[] st = route.get(routeStep);
            switch (st[0]) {
                case "wait" -> {
                    routeStep++;
                    nextActionMs = now + (long) (Double.parseDouble(st[1]) * 1000);
                }
                case "cmd" -> {
                    routeStep++;
                    String c = st[1].startsWith("/") ? st[1].substring(1) : st[1];
                    if (c.toLowerCase(Locale.ROOT).startsWith("spec")) spectateTried = true;
                    sendLater(c, c, now);
                }
                case "hotbar" -> {
                    routeStep++;
                    useHotbar(mc, now, Pattern.compile(Pattern.quote(st[1].toLowerCase(Locale.ROOT))));
                }
                case "click" -> {
                    // waits for a menu; give up on this step after 15 s
                    if (now - nextActionMs > 15_000) routeStep++;
                }
                default -> routeStep++;
            }
            return;
        }
        // fallback: open a lobby selector from the hotbar, at most 3 times
        if (mc.gameMode.getPlayerMode() == net.minecraft.world.level.GameType.SPECTATOR || spectateTried && hotbarUses >= 2) {
            enterWatch(now);
            return;
        }
        if (hotbarUses < 3 && useHotbar(mc, now, SELECTOR)) return;
        enterWatch(now);
    }

    private long pendingUse;

    private boolean useHotbar(Minecraft mc, long now, Pattern p) {
        Player pl = mc.player;
        for (int i = 0; i < 9; i++) {
            ItemStack st = pl.getInventory().getItem(i);
            if (st.isEmpty()) continue;
            String n = COLOR.matcher(st.getHoverName().getString()).replaceAll("");
            String l = n.toLowerCase(Locale.ROOT);
            if (p.matcher(l).find() && !NEVER.matcher(l).find()) {
                hotbarUses++;
                note("hotbar " + i + " \"" + n + "\" (use)");
                key(mc, com.mojang.blaze3d.platform.InputConstants.KEY_1 + i, '1' + i); // Ostinato: the number key, then the use button a moment later
                long useAt = now + HumanInput.between(rnd, 250, 600);
                pendingUse = useAt;
                nextActionMs = now + 3000 + rnd.nextInt(2000);
                return true;
            }
        }
        if (p == SELECTOR) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 9; i++) if (!pl.getInventory().getItem(i).isEmpty()) sb.append(i).append('=').append(pl.getInventory().getItem(i).getHoverName().getString()).append("; ");
            note("hotbar has no selector: " + sb);
            hotbarUses = 3;
        }
        return false;
    }

    private void enterWatch(long now) {
        phase = Phase.WATCH;
        Minecraft mc = Minecraft.getInstance();
        note("watching from " + mc.player.blockPosition() + " gamemode " + mc.gameMode.getPlayerMode() + ", players in view " + (mc.level.players().size() - 1));
        nextActionMs = now + 2000;
    }

    // ------------------------------------------------------------------ watching: follow active fighters

    private void watch(Minecraft mc, long now) {
        Player me = mc.player;
        for (Player p : mc.level.players()) {
            if (p == me) continue;
            if (p.hurtTime > 0) hurtAt.put(p.getId(), now);
        }
        hurtAt.values().removeIf(t -> now - t > 20_000);
        // keep on the current fighter while they still fight; else hop to the newest fight
        Player cur = target >= 0 && mc.level.getEntity(target) instanceof Player tp && !tp.isRemoved() ? tp : null;
        Long curT = cur == null ? null : hurtAt.get(cur.getId());
        if (cur == null || curT == null || now - curT > 8000) {
            int best = -1;
            long bt = 0;
            for (Map.Entry<Integer, Long> e : hurtAt.entrySet()) {
                if (e.getValue() > bt && mc.level.getEntity(e.getKey()) instanceof Player) {
                    bt = e.getValue();
                    best = e.getKey();
                }
            }
            if (best != target && best >= 0) {
                target = best;
                targetSince = now;
                note("follow " + mc.level.getEntity(best).getName().getString());
            }
            cur = best >= 0 ? (Player) mc.level.getEntity(best) : null;
        }
        var in = baritone.getInputOverrideHandler();
        in.clearAllKeys();
        if (cur == null) return;
        Vec3 d = cur.position().add(0, 1.2, 0).subtract(me.getEyePosition());
        float yaw = (float) (Math.toDegrees(Math.atan2(-d.x, d.z)));
        float pitch = (float) (-Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
        // a calm head turn: at most 5 degrees a tick
        float dy = net.minecraft.util.Mth.wrapDegrees(yaw - me.getYRot());
        me.setYRot(me.getYRot() + Math.max(-5, Math.min(5, dy)));
        me.setXRot(me.getXRot() + Math.max(-4, Math.min(4, pitch - me.getXRot())));
        if (mc.gameMode.getPlayerMode() != net.minecraft.world.level.GameType.SPECTATOR) return; // in a lobby: only look
        double dist = Math.sqrt(d.x * d.x + d.z * d.z);
        if (Math.abs(dy) < 30) {
            if (dist > 10) in.setInputForceState(Input.MOVE_FORWARD, true);
            else if (dist < 5) in.setInputForceState(Input.MOVE_BACK, true);
        }
        if (d.y > 3) in.setInputForceState(Input.JUMP, true);
        else if (d.y < -1) in.setInputForceState(Input.SNEAK, true);
    }
}
