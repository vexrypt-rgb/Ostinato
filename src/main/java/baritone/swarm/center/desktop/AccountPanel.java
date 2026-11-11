package baritone.swarm.center.desktop;

import baritone.swarm.accounts.AccountPanelModel;
import baritone.swarm.accounts.AccountStore;
import baritone.swarm.accounts.AzureClientConfig;
import baritone.swarm.accounts.DpapiProtector;
import baritone.swarm.accounts.JdkHttpExchange;
import baritone.swarm.accounts.MicrosoftAuth;
import baritone.swarm.accounts.SecretProtector;
import baritone.swarm.center.UnifiedChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Ostinato: add and remove bot accounts on the command center. Sign-in does not start without a client id.
 * The status line never receives a token.
 */
public final class AccountPanel {

    private AccountPanel() {}

    public static void attach(JPanel host, Path configDir) {
        host.removeAll();
        SecretProtector protector = null;
        try {
            protector = DpapiProtector.platform();
        } catch (RuntimeException ignored) {
            protector = null;
        }
        Path root = configDir == null ? UnifiedChat.configDir() : configDir;
        AccountStore store = new AccountStore(root.resolve("accounts"), protector);
        AccountPanelModel model = new AccountPanelModel(store, AzureClientConfig.clientId(root), protector != null);
        MicrosoftAuth.HttpExchange http = new JdkHttpExchange();
        DefaultListModel<String> names = new DefaultListModel<>();
        JList<String> list = new JList<>(names);
        Style.list(list);
        list.setVisibleRowCount(4);
        reload(store, names);
        JLabel status = Style.label(protector == null ? "Windows DPAPI is not available" : " ",
                Style.muted(), Font.PLAIN, 12f);
        JTextField offline = Style.field(10, "Offline account name");
        JButton add = Style.button("Add offline");
        add.addActionListener(event -> {
            try {
                store.addOffline(offline.getText().trim());
                offline.setText("");
                reload(store, names);
                status.setText("Offline account added");
            } catch (RuntimeException | IOException e) {
                status.setText(plain(e));
            }
        });
        JButton remove = Style.button("Remove");
        remove.addActionListener(event -> {
            String selected = list.getSelectedValue();
            if (selected == null) {
                return;
            }
            int mark = selected.indexOf(" (");
            String name = mark > 0 ? selected.substring(0, mark) : selected;
            try {
                store.remove(name);
                reload(store, names);
                status.setText("Removed " + name);
            } catch (IOException e) {
                status.setText("could not remove account");
            }
        });
        JButton importLauncher = Style.button("Import launcher");
        importLauncher.setToolTipText("Copy Minecraft tokens from launcher files. They expire in about a day");
        importLauncher.addActionListener(event -> {
            try {
                AccountStore.ImportReport report = store.importReport(AccountStore.launcherFiles(null));
                reload(store, names);
                status.setText(report.line());
            } catch (IOException e) {
                status.setText(plain(e));
            }
        });
        JButton saveSession = Style.button("Save this session");
        saveSession.setToolTipText("Store this game's own Microsoft session as a bot account until it expires");
        saveSession.addActionListener(event -> {
            try {
                // Ostinato: the token goes straight into the encrypted store and is never shown or logged.
                User user = Minecraft.getInstance().getUser();
                AccountStore.Listed saved = store.importSession(user.getName(),
                        user.getProfileId() == null ? null : user.getProfileId().toString(), user.getAccessToken());
                reload(store, names);
                String name = saved == null ? user.getName() : saved.name();
                status.setText("Saved " + name + ". Relaunch as your main account before spawning " + name);
            } catch (RuntimeException | IOException e) {
                status.setText(plain(e));
            }
        });
        Timer timer = new Timer(5000, event -> {
            String line = model.pollOnce(http);
            status.setText(line);
            if (!line.startsWith("Open ")) {
                ((Timer) event.getSource()).stop();
                reload(store, names);
            }
        });
        timer.setRepeats(true);
        JButton signIn = Style.button("Microsoft sign-in");
        signIn.addActionListener(event -> {
            String line = model.beginSignIn(http);
            status.setText(line);
            if (line.startsWith("Open ")) {
                timer.setDelay(model.intervalSeconds() * 1000);
                timer.start();
            }
        });
        // Ostinato: expiry labels count down, so redraw them once a minute.
        Timer expiry = new Timer(60_000, event -> {
            if (host.isDisplayable()) {
                reload(store, names);
            } else {
                ((Timer) event.getSource()).stop();
            }
        });
        expiry.start();
        JPanel buttons = Style.panel(new FlowLayout(FlowLayout.LEFT, 6, 0), null);
        buttons.add(offline);
        buttons.add(add);
        buttons.add(remove);
        buttons.add(importLauncher);
        buttons.add(saveSession);
        buttons.add(signIn);
        JPanel top = Style.panel(new BorderLayout(0, 6), null);
        top.add(Style.caption("Accounts"), BorderLayout.NORTH);
        top.add(buttons, BorderLayout.CENTER);
        host.setLayout(new BorderLayout(0, 6));
        host.setBackground(Style.surface());
        host.setOpaque(true);
        host.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Style.line()),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        host.add(top, BorderLayout.NORTH);
        host.add(Style.scroll(list, Style.bg()), BorderLayout.CENTER);
        host.add(status, BorderLayout.SOUTH);
    }

    private static void reload(AccountStore store, DefaultListModel<String> names) {
        names.clear();
        long now = System.currentTimeMillis();
        for (AccountStore.Listed listed : store.list()) {
            names.addElement(AccountStore.describe(listed, now));
        }
    }

    private static String plain(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return "could not update accounts";
        }
        if (message.startsWith("Account name") || message.startsWith("Windows DPAPI")
                || message.startsWith("could not") || message.startsWith("Online-mode")
                || message.startsWith("No offline") || message.startsWith("Sign-in needs")
                || message.startsWith("One account") || message.startsWith("token expired")
                || message.startsWith("No online session")) {
            return message;
        }
        return "could not update accounts";
    }
}
