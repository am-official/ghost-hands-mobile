package com.ghosthands.app;

/**
 * v3.16 (Everything Train R8 — read documents): turning a
 * stored file's bytes into the readable text a goal can
 * reason over.
 *
 * The readable set is deliberately small and named: txt, md,
 * csv, html (and its .htm twin), log and json — files whose
 * bytes ARE text, or (for html) text wearing markup this
 * class strips. Extraction is a pure function of the bytes:
 * decode as UTF-8 (a BOM is dropped, malformed sequences
 * become replacement characters rather than an exception),
 * strip markup for html, then the same squeeze the page
 * reader applies (blank-line runs collapse, the total is
 * capped at Eyes.READ_TOTAL_CAP_CHARS) — a document is then
 * served through the page reads' chunked discipline by the
 * hands, so the planner cannot tell a file's text from a
 * page's except by the "(file ...)" tier label.
 *
 * PDF is refused honestly: a PDF's text lives in compressed
 * content streams behind a real parser, and this release
 * ships no PDF engine. A PDF pick therefore answers
 * PDF_REFUSAL rather than failing weirdly — in the file
 * list, in the read_file result, and in the notes.
 *
 * Pure: no Android, no I/O.
 */
public final class DocText {

    private DocText() { }

    /** The extensions this release can read (htm rides with
     *  html: same format, same stripper). */
    public static final String[] TEXT_EXTS = {
            "txt", "md", "csv", "html", "htm", "log", "json",
    };

    /** MIME types that mark a file readable even when its
     *  name carries no useful extension. */
    public static final String[] TEXT_MIMES = {
            "text/plain", "text/markdown", "text/csv",
            "text/html", "application/json", "text/json",
    };

    public static final String PDF_REFUSAL =
            "cannot read text from PDFs yet";

    public static boolean isPdf(String name, String mime) {
        return "pdf".equals(Downloads.extensionOf(name))
                || "application/pdf".equals(
                        Downloads.bareMime(mime));
    }

    private static boolean inList(String[] list, String v) {
        if (v == null || v.length() == 0) return false;
        for (int i = 0; i < list.length; i++) {
            if (list[i].equals(v)) return true;
        }
        return false;
    }

    /** Readable by this release: a text-shaped extension or
     *  MIME — and never a PDF (a file named .pdf with a text
     *  MIME is still a PDF-shaped refusal, not a text read). */
    public static boolean isReadable(String name, String mime) {
        if (isPdf(name, mime)) return false;
        return inList(TEXT_EXTS, Downloads.extensionOf(name))
                || inList(TEXT_MIMES, Downloads.bareMime(mime));
    }

    /** The honest answer for a file this release cannot read:
     *  the PDF refusal for a PDF, a naming of the readable
     *  set for anything else. */
    public static String refusalFor(String name, String mime) {
        if (isPdf(name, mime)) {
            return PDF_REFUSAL + " — " + name + " is a PDF, and "
                    + "this build has no PDF text engine";
        }
        return "cannot read " + name + " as text — readable "
                + "files are txt, md, csv, html, log and json";
    }

    /**
     * Extract a file's readable text, or null when the file
     * is not readable by this release (the caller answers
     * refusalFor instead). The result is capped at the page
     * reader's total cap: a document and a page live under
     * the same budget discipline.
     */
    public static String extract(String name, String mime,
                                 byte[] bytes) {
        if (bytes == null || !isReadable(name, mime)) return null;
        String text;
        try {
            text = new String(bytes, "UTF-8");
        } catch (Exception e) {
            return null;
        }
        if (text.length() > 0 && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        boolean html = "html".equals(Downloads.extensionOf(name))
                || "htm".equals(Downloads.extensionOf(name))
                || "text/html".equals(Downloads.bareMime(mime));
        if (html) text = stripHtml(text);
        return squeeze(text);
    }

    /** The reader's squeeze (Eyes' discipline): CRLF becomes
     *  LF, runs of blank lines collapse to one, the ends are
     *  trimmed, and the total is capped. */
    public static String squeeze(String text) {
        if (text == null) return "";
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder sb = new StringBuilder();
        String[] lines = t.split("\n", -1);
        boolean lastBlank = true; // leading blanks are trimmed too
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].replaceAll("[ \t]+$", "");
            if (line.trim().length() == 0) {
                if (!lastBlank) sb.append('\n');
                lastBlank = true;
            } else {
                sb.append(line).append('\n');
                lastBlank = false;
            }
        }
        String out = sb.toString().trim();
        if (out.length() > Eyes.READ_TOTAL_CAP_CHARS) {
            out = out.substring(0, Eyes.READ_TOTAL_CAP_CHARS);
        }
        return out;
    }

    // ---------------- html ----------------

    private static final String[] BLOCK_TAGS = {
            "p", "div", "br", "li", "tr", "h1", "h2", "h3",
            "h4", "h5", "h6", "section", "article", "header",
            "footer", "table", "ul", "ol", "blockquote", "pre",
            "hr", "title",
    };

    /**
     * A small, honest tag stripper — not a parser. Script,
     * style and noscript bodies are dropped whole; block-level
     * tags become line breaks; every other tag vanishes; the
     * five named entities and numeric character references
     * are decoded. Malformed markup degrades to partial text,
     * never to an exception: a document the stripper cannot
     * fully understand still reads as something.
     */
    public static String stripHtml(String html) {
        if (html == null) return "";
        String s = dropElement(html, "script");
        s = dropElement(s, "style");
        s = dropElement(s, "noscript");
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '<') {
                int end = s.indexOf('>', i);
                if (end < 0) break;
                String tag = s.substring(i + 1, end).trim()
                        .toLowerCase();
                if (tag.startsWith("/")) tag = tag.substring(1);
                int sp = tag.indexOf(' ');
                if (sp >= 0) tag = tag.substring(0, sp);
                if (tag.endsWith("/")) {
                    tag = tag.substring(0, tag.length() - 1);
                }
                if (inList(BLOCK_TAGS, tag)) out.append('\n');
                i = end + 1;
            } else if (c == '&') {
                int semi = s.indexOf(';', i);
                if (semi > i && semi - i <= 10) {
                    String decoded = decodeEntity(
                            s.substring(i + 1, semi));
                    if (decoded != null) {
                        out.append(decoded);
                        i = semi + 1;
                        continue;
                    }
                }
                out.append(c);
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Remove one element's occurrences whole — open tag,
     *  body, close tag. An unclosed element drops to the end
     *  of the text (its body was never readable anyway). */
    private static String dropElement(String s, String name) {
        String lower = s.toLowerCase();
        String open = "<" + name;
        String close = "</" + name + ">";
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int at = lower.indexOf(open, i);
            if (at < 0) {
                out.append(s.substring(i));
                break;
            }
            // "<scripture>" is not a script element: the char
            // after the name must end the tag name.
            int after = at + open.length();
            if (after < s.length()) {
                char nx = s.charAt(after);
                if (nx != '>' && nx != '/' && nx != ' '
                        && nx != '\t' && nx != '\n') {
                    out.append(s.substring(i, after));
                    i = after;
                    continue;
                }
            }
            int endTag = lower.indexOf(close, at);
            out.append(s.substring(i, at));
            if (endTag < 0) break;
            i = endTag + close.length();
        }
        return out.toString();
    }

    private static String decodeEntity(String ent) {
        if ("amp".equals(ent)) return "&";
        if ("lt".equals(ent)) return "<";
        if ("gt".equals(ent)) return ">";
        if ("quot".equals(ent)) return "\"";
        if ("apos".equals(ent)) return "'";
        if ("nbsp".equals(ent)) return " ";
        if (ent.startsWith("#") && ent.length() > 1) {
            try {
                int code;
                if (ent.charAt(1) == 'x' || ent.charAt(1) == 'X') {
                    code = Integer.parseInt(ent.substring(2), 16);
                } else {
                    code = Integer.parseInt(ent.substring(1));
                }
                if (code > 0 && code < 0x10000) {
                    return String.valueOf((char) code);
                }
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}
