package com.ghosthands.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v3.16 (Everything Train R8 — governed downloads): what a page
 * offers to download, judged before a single byte is kept.
 *
 * A download the page initiates is a WRITE: it changes the
 * phone (a new file in the app's private store) in a
 * reversible, ordinary way. A download whose filename or MIME
 * is executable- or archive-shaped is CONSEQUENTIAL instead,
 * for the same reason a "place order" tap is: an app package
 * or an archive can change the phone well beyond one file, and
 * an archive hides what is inside it. The two named lists
 * below (extensions, then MIME types) are the whole rule, and
 * the host harness pins them.
 *
 * decide() mirrors Governor.evaluate's order exactly, against
 * the download's own haystacks: the pack's deny patterns over
 * the filename and the source URL first (a download named
 * like a prompt-injection is denied whatever its class), then
 * the domain rules over the source host, then the policy's
 * outcome for the class. The Activity enforces the decision:
 * allow fetches at once, ask waits on the approval panel
 * (silence is a denial), deny fetches nothing. Every outcome
 * is trail-recorded by the Activity with the filename, the
 * byte size and the MIME.
 *
 * The store is the downloads list's data: one JSON string in
 * preferences (SavedGoals' pattern), one entry per SAVED
 * file. Denied and failed downloads are trail events, not
 * store entries — the list is the files the phone actually
 * holds. Retention follows the house storage discipline
 * (Shots / TrailPrune): a count cap and a byte cap, oldest
 * evicted first (see excess()).
 *
 * Pure: no Android, no I/O — the harness pins the
 * classification table, the decision rows, the store's
 * round-trip and the retention math.
 */
public final class Downloads {

    /** The preferences key the downloads store lives under. */
    public static final String PREF_DOWNLOADS = "downloads_store";

    /** The store's directory name inside the app's files. */
    public static final String DIR_NAME = "downloads";

    /** No single download may exceed this, on any path (HTTP
     *  re-fetch, data: decode, blob bridge). A page offering
     *  more is refused before the fetch, when the size is
     *  known, or cut off mid-stream when it is not. */
    public static final long MAX_DOWNLOAD_BYTES = 25L * 1024 * 1024;

    /** Retention: at most this many saved files... */
    public static final int MAX_KEPT_FILES = 30;

    /** ...and at most this many bytes across them. */
    public static final long MAX_TOTAL_BYTES = 100L * 1024 * 1024;

    // ---------------- the named lists ----------------

    /** Extensions that make a download executable-shaped. */
    public static final String[] EXECUTABLE_EXTS = {
            "apk", "exe", "msi", "dmg", "pkg", "deb", "rpm",
            "com", "scr", "bat", "cmd", "ps1", "sh", "jar",
            "appimage", "bin", "run",
    };

    /** Extensions that make a download archive-shaped. */
    public static final String[] ARCHIVE_EXTS = {
            "zip", "tar", "gz", "tgz", "bz2", "xz", "7z",
            "rar", "z", "lz4", "iso", "img",
    };

    /** MIME types that make a download executable-shaped. */
    public static final String[] EXECUTABLE_MIMES = {
            "application/vnd.android.package-archive",
            "application/x-msdownload",
            "application/x-msdos-program",
            "application/x-executable",
            "application/x-elf",
            "application/x-apple-diskimage",
            "application/x-deb",
            "application/x-rpm",
            "application/x-msi",
            "application/java-archive",
            "application/x-sh",
            "application/x-shellscript",
    };

    /** MIME types that make a download archive-shaped. */
    public static final String[] ARCHIVE_MIMES = {
            "application/zip",
            "application/x-zip-compressed",
            "application/x-tar",
            "application/gzip",
            "application/x-gzip",
            "application/x-bzip2",
            "application/x-7z-compressed",
            "application/x-rar-compressed",
            "application/vnd.rar",
            "application/x-iso9660-image",
    };

    // ---------------- classification ----------------

    /** The filename's extension, lowercased, no dot — or ""
     *  when the name has none worth the name (no dot, a leading
     *  dot only, or a trailing dot). A name like "a.tar.gz"
     *  reports "gz" (the outermost layer is what lands). */
    public static String extensionOf(String name) {
        if (name == null) return "";
        String n = name.trim();
        int dot = n.lastIndexOf('.');
        if (dot <= 0 || dot == n.length() - 1) return "";
        return n.substring(dot + 1).toLowerCase();
    }

    private static boolean in(String[] list, String v) {
        if (v == null || v.length() == 0) return false;
        for (int i = 0; i < list.length; i++) {
            if (list[i].equals(v)) return true;
        }
        return false;
    }

    /** The MIME half of a type test: parameters stripped
     *  ("text/html; charset=utf-8" judges as "text/html"),
     *  case folded. */
    public static String bareMime(String mime) {
        if (mime == null) return "";
        String m = mime.trim().toLowerCase();
        int semi = m.indexOf(';');
        if (semi >= 0) m = m.substring(0, semi).trim();
        return m;
    }

    /** Executable-shaped by name or by MIME: an app package,
     *  an installer, a script — something that RUNS. */
    public static boolean isExecutableShaped(String name, String mime) {
        return in(EXECUTABLE_EXTS, extensionOf(name))
                || in(EXECUTABLE_MIMES, bareMime(mime));
    }

    /** Archive-shaped by name or by MIME: a container whose
     *  contents the filename cannot vouch for. */
    public static boolean isArchiveShaped(String name, String mime) {
        return in(ARCHIVE_EXTS, extensionOf(name))
                || in(ARCHIVE_MIMES, bareMime(mime));
    }

    /** The Governor class of a download: CONSEQUENTIAL when
     *  executable- or archive-shaped, WRITE otherwise. */
    public static String classify(String name, String mime) {
        if (isExecutableShaped(name, mime)
                || isArchiveShaped(name, mime)) {
            return Governor.CONSEQUENTIAL;
        }
        return Governor.WRITE;
    }

    /** What the shape is called in plain words, for the
     *  approval text: "an app package", "an archive", or "a
     *  file". Executable wins the naming when both match. */
    public static String shapeLabel(String name, String mime) {
        if (isExecutableShaped(name, mime)) {
            return "an app or program file";
        }
        if (isArchiveShaped(name, mime)) return "an archive";
        return "a file";
    }

    // ---------------- the decision ----------------

    /**
     * Judge one download under a policy, in Governor.evaluate's
     * order: deny patterns (over the filename and the source
     * URL, with the same %20 / separator folding) — domain
     * rules over the source host — the class outcome. The
     * reasons mirror the Governor's own wording, with the class
     * reason naming the download's shape.
     */
    public static Governor.Decision decide(Governor.Policy policy,
                                           String name, String mime,
                                           String sourceUrl) {
        if (policy == null) policy = new Governor.Policy();
        String classification = classify(name, mime);

        if (!policy.denyPatterns.isEmpty()) {
            List<String> haystacks = new ArrayList<String>();
            if (name != null) haystacks.add(name);
            if (sourceUrl != null) haystacks.add(sourceUrl);
            List<String> lowered = new ArrayList<String>();
            for (String h : haystacks) {
                String low = h.toLowerCase();
                lowered.add(low);
                String folded = low.replace("%20", " ");
                folded = folded.replace('-', ' ')
                        .replace('_', ' ').replace('+', ' ');
                if (!folded.equals(low)) lowered.add(folded);
            }
            for (String pattern : policy.denyPatterns) {
                for (String h : lowered) {
                    if (h.contains(pattern)) {
                        return new Governor.Decision(classification,
                                Governor.DENY,
                                "seatbelt: injection-shaped target "
                                        + "(matched '" + pattern + "')");
                    }
                }
            }
        }

        String host = SiteGrants.hostOf(sourceUrl);
        if (host != null && host.length() > 0) {
            if (policy.blockedDomains.contains(host)) {
                return new Governor.Decision(classification,
                        Governor.DENY,
                        "domain " + host + " is blocked by policy");
            }
            if (!policy.allowedDomains.isEmpty()
                    && !policy.allowedDomains.contains(host)) {
                return new Governor.Decision(classification,
                        Governor.DENY,
                        "domain " + host
                                + " is not in the policy allowlist");
            }
        }

        String outcome = policy.forClass(classification);
        String reason;
        if (Governor.ALLOW.equals(outcome)) {
            reason = classification + " actions are allowed by policy";
        } else if (Governor.ASK.equals(outcome)) {
            reason = classification
                    + " actions require approval by policy";
        } else {
            reason = classification + " actions are denied by policy";
        }
        return new Governor.Decision(classification, outcome, reason);
    }

    /** The approval panel's plain naming of a download: the
     *  file, its type in words and MIME, its size when known,
     *  and where it comes from. */
    public static String describe(String name, String mime,
                                  long bytes, String sourceUrl,
                                  Governor.Decision decision) {
        StringBuilder sb = new StringBuilder();
        sb.append("Download ").append(shapeLabel(name, mime))
                .append(": ").append(name == null ? "(unnamed)" : name);
        String bare = bareMime(mime);
        if (bare.length() > 0) sb.append(" (").append(bare).append(')');
        sb.append(", ").append(sizeLabel(bytes));
        String host = SiteGrants.hostOf(sourceUrl);
        if (host != null && host.length() > 0) {
            sb.append(", from ").append(host);
        }
        if (decision != null
                && Governor.CONSEQUENTIAL.equals(decision.classification)) {
            sb.append(". App and archive files can change your "
                    + "phone or hide what is inside them.");
        }
        return sb.toString();
    }

    /** "1.2 MB" / "340 KB" / "512 B" — and "size unknown" for
     *  the negative lengths a server that streams chunked
     *  hands the download listener. */
    public static String sizeLabel(long bytes) {
        if (bytes < 0) return "size unknown";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) {
            return Math.round(bytes / 102.4) / 10.0 + " KB";
        }
        return Math.round(bytes / (1024.0 * 1024.0) * 10) / 10.0 + " MB";
    }

    // ---------------- names ----------------

    /** A filename safe to keep: path separators and control
     *  characters become underscores, leading dots are dropped
     *  (no hidden files), whitespace is trimmed, and an empty
     *  result falls back to "download". The extension survives
     *  whenever the name had one. */
    public static String safeName(String name) {
        if (name == null) return "download";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c < 0x20 || c == 0x7F) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String s = sb.toString().trim();
        while (s.startsWith(".")) s = s.substring(1).trim();
        if (s.length() == 0) return "download";
        if (s.length() > 120) {
            String ext = extensionOf(s);
            if (ext.length() > 0 && ext.length() < 10) {
                s = s.substring(0, 120 - ext.length() - 1) + "." + ext;
            } else {
                s = s.substring(0, 120);
            }
        }
        return s;
    }

    /** The name to actually store under: the safe name, or —
     *  when the store already holds it — the name with " (2)",
     *  " (3)", ... before the extension, the browser's own
     *  convention. */
    public static String uniqueName(Set<String> existing, String name) {
        String safe = safeName(name);
        if (existing == null || !existing.contains(safe)) return safe;
        String ext = extensionOf(safe);
        String stem = (ext.length() > 0)
                ? safe.substring(0, safe.length() - ext.length() - 1)
                : safe;
        for (int i = 2; i < 1000; i++) {
            String cand = stem + " (" + i + ")"
                    + (ext.length() > 0 ? "." + ext : "");
            if (!existing.contains(cand)) return cand;
        }
        return safe;
    }

    /** A stored file's MIME when only its name is known —
     *  the type the viewer intents and the file provider
     *  answer with. Unknown names are octet-stream. */
    public static String mimeForName(String name) {
        String ext = extensionOf(name);
        if ("txt".equals(ext) || "log".equals(ext)
                || "md".equals(ext)) {
            return "md".equals(ext) ? "text/markdown" : "text/plain";
        }
        if ("csv".equals(ext)) return "text/csv";
        if ("html".equals(ext) || "htm".equals(ext)) return "text/html";
        if ("json".equals(ext)) return "application/json";
        if ("pdf".equals(ext)) return "application/pdf";
        if ("zip".equals(ext)) return "application/zip";
        if ("apk".equals(ext)) {
            return "application/vnd.android.package-archive";
        }
        if ("png".equals(ext)) return "image/png";
        if ("jpg".equals(ext) || "jpeg".equals(ext)) return "image/jpeg";
        if ("gif".equals(ext)) return "image/gif";
        if ("mp3".equals(ext)) return "audio/mpeg";
        if ("mp4".equals(ext)) return "video/mp4";
        return "application/octet-stream";
    }

    // ---------------- URL shapes ----------------

    public static boolean isHttp(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.startsWith("http://") || u.startsWith("https://");
    }

    public static boolean isBlob(String url) {
        return url != null && url.toLowerCase().startsWith("blob:");
    }

    public static boolean isData(String url) {
        return url != null && url.toLowerCase().startsWith("data:");
    }

    /** A decoded data: URL: its MIME ("" when the URL names
     *  none — the RFC default is text/plain, but a download
     *  with no type is judged by its filename anyway) and its
     *  bytes. */
    public static final class DataUrl {
        public String mime = "";
        public byte[] bytes = new byte[0];
    }

    /** Decode a data: URL, or null when it is not one, is
     *  malformed, or decodes past MAX_DOWNLOAD_BYTES. Base64
     *  payloads use the platform decoder; plain payloads are
     *  percent-decoded as UTF-8 text. */
    public static DataUrl decodeDataUrl(String url) {
        if (!isData(url)) return null;
        int comma = url.indexOf(',');
        if (comma < 0) return null;
        String meta = url.substring(5, comma);
        String payload = url.substring(comma + 1);
        boolean base64 = false;
        String mime = meta;
        int semi = meta.lastIndexOf(';');
        if (semi >= 0 && "base64".equalsIgnoreCase(
                meta.substring(semi + 1).trim())) {
            base64 = true;
            mime = meta.substring(0, semi);
        }
        DataUrl out = new DataUrl();
        out.mime = bareMime(mime);
        try {
            if (base64) {
                out.bytes = base64Decode(payload);
            } else {
                out.bytes = percentDecode(payload);
            }
        } catch (Exception e) {
            return null;
        }
        if (out.bytes == null
                || out.bytes.length > MAX_DOWNLOAD_BYTES) {
            return null;
        }
        return out;
    }

    /** A small strict base64 decoder (the standard alphabet;
     *  whitespace ignored, padding honoured, anything else is
     *  an error). Hand-rolled so this class stays pure — the
     *  platform decoder is an Android stub on the host. */
    private static byte[] base64Decode(String s) throws Exception {
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                continue;
            }
            clean.append(c);
        }
        String in = clean.toString();
        int pad = 0;
        while (in.length() > 0 && in.charAt(in.length() - 1) == '=') {
            pad++;
            in = in.substring(0, in.length() - 1);
        }
        if (pad > 2 || in.length() % 4 == 1) {
            throw new IllegalArgumentException("bad base64 length");
        }
        java.io.ByteArrayOutputStream buf =
                new java.io.ByteArrayOutputStream();
        int acc = 0;
        int nbits = 0;
        for (int i = 0; i < in.length(); i++) {
            int v = base64Value(in.charAt(i));
            if (v < 0) {
                throw new IllegalArgumentException("bad base64 char");
            }
            acc = (acc << 6) | v;
            nbits += 6;
            if (nbits >= 8) {
                nbits -= 8;
                buf.write((acc >> nbits) & 0xFF);
            }
        }
        return buf.toByteArray();
    }

    private static int base64Value(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '+') return 62;
        if (c == '/') return 63;
        if (c == '-') return 62;
        if (c == '_') return 63;
        return -1;
    }

    private static byte[] percentDecode(String s) throws Exception {
        java.io.ByteArrayOutputStream buf =
                new java.io.ByteArrayOutputStream();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    buf.write((hi << 4) + lo);
                    i += 3;
                    continue;
                }
            }
            byte[] b = String.valueOf(c).getBytes("UTF-8");
            buf.write(b, 0, b.length);
            i++;
        }
        return buf.toByteArray();
    }

    // ---------------- the store ----------------

    /** One saved file. origin is "download" (a page offered
     *  it) or "picked" (the user chose it in the file picker);
     *  both live in the same store and list. */
    public static final class Entry {
        public String name = "";
        public String mime = "";
        public long bytes = 0;
        public long tsMs = 0;
        public String origin = "download";
        public String sourceUrl = "";

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("name", name);
            m.put("mime", mime);
            m.put("bytes", Long.valueOf(bytes));
            m.put("ts", Long.valueOf(tsMs));
            m.put("origin", origin);
            m.put("source", sourceUrl);
            return m;
        }

        public static Entry fromMap(Map<?, ?> m) {
            if (m == null) return null;
            Object n = m.get("name");
            if (!(n instanceof String)
                    || ((String) n).length() == 0) return null;
            Entry e = new Entry();
            e.name = (String) n;
            Object mi = m.get("mime");
            if (mi instanceof String) e.mime = (String) mi;
            Object b = m.get("bytes");
            if (b instanceof Number) e.bytes = ((Number) b).longValue();
            Object t = m.get("ts");
            if (t instanceof Number) e.tsMs = ((Number) t).longValue();
            Object o = m.get("origin");
            if (o instanceof String) e.origin = (String) o;
            Object s = m.get("source");
            if (s instanceof String) e.sourceUrl = (String) s;
            return e;
        }
    }

    private final List<Entry> entries = new ArrayList<Entry>();

    public Downloads() { }

    /** Tolerant parse (SavedGoals' discipline): a corrupt or
     *  foreign value parses to an empty store; a malformed
     *  entry inside a good list costs only that entry. */
    public static Downloads parse(String json) {
        Downloads store = new Downloads();
        if (json == null || json.length() == 0) return store;
        Object parsed;
        try {
            parsed = Json.parse(json);
        } catch (Exception e) {
            return store;
        }
        if (!(parsed instanceof List)) return store;
        List<?> arr = (List<?>) parsed;
        for (int i = 0; i < arr.size(); i++) {
            Object item = arr.get(i);
            if (item instanceof Map) {
                Entry e = Entry.fromMap((Map<?, ?>) item);
                if (e != null) store.entries.add(e);
            }
        }
        return store;
    }

    public String serialize() {
        List<Object> arr = new ArrayList<Object>();
        for (int i = 0; i < entries.size(); i++) {
            arr.add(entries.get(i).toMap());
        }
        return Json.write(arr);
    }

    public int size() { return entries.size(); }

    /** Oldest first (insertion order — the store appends). */
    public List<Entry> all() {
        return new ArrayList<Entry>(entries);
    }

    public Entry find(String name) {
        if (name == null) return null;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).name.equals(name)) return entries.get(i);
        }
        return null;
    }

    public void put(Entry e) {
        if (e == null || e.name == null || e.name.length() == 0) return;
        Entry old = find(e.name);
        if (old != null) entries.remove(old);
        entries.add(e);
    }

    public boolean remove(String name) {
        Entry e = find(name);
        if (e == null) return false;
        entries.remove(e);
        return true;
    }

    /** The entries retention evicts: everything past
     *  MAX_KEPT_FILES and past MAX_TOTAL_BYTES, oldest first.
     *  Pure — the Activity deletes the named files and the
     *  entries together. */
    public List<Entry> excess() {
        List<Entry> out = new ArrayList<Entry>();
        long total = 0;
        for (int i = 0; i < entries.size(); i++) {
            total += Math.max(0, entries.get(i).bytes);
        }
        int idx = 0;
        while (idx < entries.size()
                && (entries.size() - out.size() > MAX_KEPT_FILES
                        || total > MAX_TOTAL_BYTES)) {
            Entry e = entries.get(idx++);
            out.add(e);
            total -= Math.max(0, e.bytes);
        }
        return out;
    }
}
