package baritone.swarm.accounts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ostinato: the Azure application id is the user's. This mod does not ship one.
 */
public final class AzureClientConfig {

    public static final String MISSING = "Sign-in needs an Azure app registration (a public client with the device-code "
            + "flow enabled). Put the application id in azure-client-id.txt in the Ostinato config directory, "
            + "or pass -Dostinato.azureClientId. Ostinato does not include a client id.";

    private AzureClientConfig() {}

    public static String clientId(Path configDir) {
        String property = System.getProperty("ostinato.azureClientId");
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        if (configDir == null) {
            return "";
        }
        Path file = configDir.resolve("azure-client-id.txt");
        try {
            if (!Files.isRegularFile(file)) {
                return "";
            }
            for (String raw : Files.readAllLines(file)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                return line;
            }
        } catch (IOException ignored) {
            return "";
        }
        return "";
    }
}
