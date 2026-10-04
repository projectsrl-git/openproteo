package com.legalarchive.orchestrator.unarchive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Keeps symbolic links inside the archive's own folder (spec section 21.6, Linux hosts only).
 *
 * <p>GNU tar creates a link to {@code ../outside} without complaint (measured); this executor
 * refuses it, as Python's {@code data} extraction filter does. Two checks, because one is not
 * enough:
 * <ol>
 * <li>{@link #lexical}, per link, when it is met: the target must be relative, not empty, and must
 *     not climb above the archive root when resolved against the link's own folder <em>as text</em>.</li>
 * <li>{@link #verify}, once every entry exists, before the commit: each link is resolved as the
 *     kernel would, <em>following the archive's other links</em>. Text alone misses chains:
 *     {@code d/up -> ..} and {@code d/up2 -> up/..} both pass the lexical check, yet {@code d/up2}
 *     really resolves to the parent of the root. The resolver never leaves the staging folder:
 *     an absolute target or a climb above the root is a refusal, not something to follow.</li>
 * </ol>
 * No entry is ever written THROUGH a link: links are registered in {@link NameIndex} as files, so
 * {@code link/x} after {@code link} is a file/directory conflict.
 */
public final class LinkGuard {

    /** Linux's limit on symlinks followed in one resolution (ELOOP). */
    static final int MAX_HOPS = 40;

    private LinkGuard() {
    }

    /** The per-link check. {@code parent} are the link's folder segments below the archive root. */
    public static void lexical(String entry, List<String> parent, String target) throws UnarchiveException {
        if (target == null || target.isEmpty()) {
            throw new UnarchiveException(UnarchiveException.Rule.BAD_LINK_TARGET, "link '" + EntryName.printable(entry) + "' has an empty target");
        }
        if (target.indexOf('\0') >= 0) {
            throw new UnarchiveException(UnarchiveException.Rule.BAD_LINK_TARGET, "link '" + EntryName.printable(entry) + "' has NUL in its target");
        }
        if (target.startsWith("/")) {
            throw new UnarchiveException(UnarchiveException.Rule.LINK_ESCAPE, "link '" + EntryName.printable(entry) + "' points to the absolute path '"
                    + EntryName.printable(target) + "'");
        }
        Deque<String> stack = new ArrayDeque<String>(parent);
        for (String s : target.split("/", -1)) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) {
                if (stack.isEmpty()) {
                    throw new UnarchiveException(UnarchiveException.Rule.LINK_ESCAPE, "link '" + EntryName.printable(entry) + "' -> '"
                            + EntryName.printable(target) + "' climbs above the archive's folder");
                }
                stack.removeLast();
            } else {
                stack.addLast(s);
            }
        }
    }

    /** The final check: every link in {@code links}, resolved through the others, stays under {@code root}. */
    public static void verify(Path root, List<Path> links) throws IOException {
        for (Path link : links) {
            List<String> parent = new ArrayList<String>();
            Path rel = root.relativize(link.getParent() == null ? root : link.getParent());
            for (Path p : rel) if (!p.toString().isEmpty()) parent.add(p.toString());
            resolve(root, link, parent, Files.readSymbolicLink(link).toString());
        }
    }

    private static void resolve(Path root, Path link, List<String> start, String target) throws IOException {
        Deque<String> stack = new ArrayDeque<String>(start);
        Deque<String> todo = new ArrayDeque<String>(Arrays.asList(target.split("/", -1)));
        int hops = 0;
        if (target.startsWith("/")) throw escape(root, link, "an absolute target");
        while (!todo.isEmpty()) {
            String s = todo.removeFirst();
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) {
                if (stack.isEmpty()) throw escape(root, link, "a path that climbs above the archive's folder");
                stack.removeLast();
                continue;
            }
            Path here = root;
            for (String x : stack) here = here.resolve(x);
            here = here.resolve(s);
            if (Files.isSymbolicLink(here)) {
                if (++hops > MAX_HOPS) throw escape(root, link, "more than " + MAX_HOPS + " links in a row (a loop)");
                String t = Files.readSymbolicLink(here).toString();
                if (t.startsWith("/")) throw escape(root, link, "a link with an absolute target on the way");
                List<String> parts = Arrays.asList(t.split("/", -1));
                for (int i = parts.size() - 1; i >= 0; i--) todo.addFirst(parts.get(i));
                continue;                                   // resolved relative to the folder it sits in
            }
            stack.addLast(s);
            if (!Files.exists(here, LinkOption.NOFOLLOW_LINKS)) {
                // dangling from here on: the rest is plain text, nothing more can be followed
                while (!todo.isEmpty()) {
                    String r = todo.removeFirst();
                    if (r.isEmpty() || r.equals(".")) continue;
                    if (r.equals("..")) {
                        if (stack.isEmpty()) throw escape(root, link, "a path that climbs above the archive's folder");
                        stack.removeLast();
                    } else {
                        stack.addLast(r);
                    }
                }
            }
        }
    }

    private static UnarchiveException escape(Path root, Path link, String why) throws IOException {
        return new UnarchiveException(UnarchiveException.Rule.LINK_ESCAPE, "link '" + root.relativize(link) + "' -> '"
                + Files.readSymbolicLink(link) + "' leaves the archive's folder through " + why);
    }
}
