package com.legalarchive.orchestrator.control;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Whether everyone may create files in the inbox. The inbox ACL is the authorisation of the CLI
 * (spec {@code .claude/HEADLESS.md} section 5.3): when it is open to everyone, the channel is
 * switched off (Q8, answered by the author 2026-11-18).
 *
 * <p>POSIX: the others-write bit. Windows: an ALLOW entry granting {@code ADD_FILE} or
 * {@code WRITE_DATA} to a group that means everyone on the box. NIO gives principal NAMES, not
 * SIDs, so the groups are matched by name: English and Italian names are known; another language
 * of Windows is not recognised, which the Platform page states.
 */
public final class InboxAcl {

    private InboxAcl() { }

    /** Account part (after any {@code DOMAIN\}) of the groups that mean "everyone", lower-cased. */
    static final Set<String> EVERYONE = new HashSet<String>(Arrays.asList(
            "everyone", "tutti",
            "authenticated users", "utenti autenticati",
            "users", "utenti"));

    /** Null when the inbox is not open to everyone; otherwise the reason, naming the entry. */
    public static String openToEveryone(Path inbox) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(inbox, PosixFileAttributeView.class);
        if (posix != null) {
            Set<PosixFilePermission> p = posix.readAttributes().permissions();
            if (p.contains(PosixFilePermission.OTHERS_WRITE)) {
                return "any account on the server can create files in it (others have write permission)";
            }
            return null;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(inbox, AclFileAttributeView.class);
        if (acl == null) return null;
        for (AclEntry e : acl.getAcl()) {
            if (e.type() != AclEntryType.ALLOW) continue;
            Set<AclEntryPermission> perms = e.permissions();
            if (!perms.contains(AclEntryPermission.ADD_FILE) && !perms.contains(AclEntryPermission.WRITE_DATA)) continue;
            String name = e.principal().getName();
            if (EVERYONE.contains(account(name))) {
                return "'" + name + "' may create files in it";
            }
        }
        return null;
    }

    static String account(String principal) {
        String n = principal == null ? "" : principal;
        int bs = n.lastIndexOf('\\');
        if (bs >= 0) n = n.substring(bs + 1);
        return n.trim().toLowerCase(Locale.ROOT);
    }
}
