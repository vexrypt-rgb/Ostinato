package baritone.swarm.accounts;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;

/** Ostinato: write a secret file for the current user only. The bytes are already protected. */
public final class PrivateFiles {

    private PrivateFiles() {}

    public static void writeRestricted(Path file, byte[] bytes) throws IOException {
        if (file == null || bytes == null) {
            throw new IOException("could not write the child auth file");
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IOException("could not write the child auth file");
        }
        Files.createDirectories(parent);
        Path pending = Files.createTempFile(parent, ".auth-", ".tmp");
        try {
            Files.write(pending, bytes);
            restrict(pending);
            try {
                Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING);
            }
            restrict(file);
        } catch (AccessDeniedException e) {
            throw new IOException("could not write the child auth file");
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private static void restrict(Path file) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            Files.setPosixFilePermissions(file, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl == null) {
            return;
        }
        UserPrincipal user;
        try {
            user = Files.getOwner(file);
        } catch (IOException | UnsupportedOperationException e) {
            user = file.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
        }
        AclEntry entry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(user)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
        acl.setAcl(List.of(entry));
    }
}
