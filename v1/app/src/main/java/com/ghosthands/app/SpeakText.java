package com.ghosthands.app;

/**
 * v3.3 (item 3 — read-aloud answers): the answer card, rendered
 * for the ear. The Activity hands this text to the framework
 * TextToSpeech engine; everything about WHAT is said is decided
 * here, purely, so the host harness can pin it.
 *
 * The rules of the spoken form:
 *
 *   - The source is named, never spelled out: the card's source
 *     TITLE when it has one ("Oakdale, TN Forecast"), else the
 *     host's readable name ("weather.gov"). A raw URL is never
 *     spoken — anywhere. scrub() rewrites any URL-shaped token
 *     that slipped into the goal, the answer or a fact (a
 *     planner can write one into its summary) to its host name,
 *     because "h-t-t-p-s colon slash slash" is not information.
 *   - The watch banner rides when the run was watched — it is
 *     part of the answer, on screen and aloud alike.
 *   - Facts are spoken as a list; the findings fallback excerpt
 *     is spoken under its own name, as the card labels it.
 *   - Numbers are left exactly as written: the engine voices
 *     digits, currency and percents itself, and a plain-text
 *     pass that tried to spell them out would mangle more than
 *     it fixed. The one phrasing rewrite is "->" to "to" (the
 *     watch diff's arrow), which engines otherwise read as
 *     punctuation noise.
 *
 * The Listen control's label/enabled rules live here too (the
 * VoiceGoal pattern): speaking -> "■ Stop" (the tap that stops
 * it); no engine -> "Listen n/a", disabled.
 */
public final class SpeakText {

    private SpeakText() { }

    /** The card, spoken: goal, answer/outcome, the watch banner,
     *  the findings excerpt, the facts, and the source by name.
     *  Only the sections the card itself carries. */
    public static String of(AnswerCard card) {
        if (card == null) return "";
        StringBuilder sb = new StringBuilder();
        if (card.goal.length() > 0) {
            sb.append("Goal: ").append(scrub(card.goal)).append(". ");
        }
        sb.append("done".equals(card.outcome) ? "Answer: " : "Outcome: ");
        sb.append(scrub(card.outcomeLine)).append(". ");
        String banner = card.watchBanner();
        if (banner.length() > 0) {
            sb.append(scrub(banner)).append(" ");
        }
        if (card.findings.length() > 0) {
            sb.append("What it found: ")
                    .append(scrub(card.findings)).append(". ");
        }
        if (!card.facts.isEmpty()) {
            sb.append("Facts noted: ");
            for (int i = 0; i < card.facts.size(); i++) {
                String fact = card.facts.get(i);
                if (fact.startsWith("- ")) fact = fact.substring(2);
                sb.append(scrub(fact)).append("; ");
            }
            sb.append(". ");
        }
        String src = sourceName(card.sourceTitle, card.sourceUrl);
        if (src.length() > 0) {
            sb.append("Source: ").append(src).append(".");
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    /**
     * The source's spoken name: the title when there is one,
     * else the URL's host with any "www." dropped ("weather.gov",
     * never the whole URL). "" when neither exists.
     */
    public static String sourceName(String title, String url) {
        if (title != null && title.trim().length() > 0) {
            return title.trim();
        }
        String host = Routes.hostOf(url);
        if (host.startsWith("www.")) host = host.substring(4);
        return host;
    }

    /**
     * Make free text speakable: every whitespace-delimited
     * token carrying a URL scheme is replaced by its host name
     * (trailing sentence punctuation rides along), and the
     * watch diff's "->" arrow becomes the word "to". All other
     * text passes through untouched.
     */
    public static String scrub(String text) {
        if (text == null) return "";
        String t = text.replace("->", " to ");
        String[] tokens = t.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(spokenToken(tokens[i]));
        }
        return sb.toString();
    }

    /** One token of scrub(): a URL-shaped token becomes its
     *  host name (keeping any trailing punctuation that was
     *  never part of the URL); anything else is itself. */
    private static String spokenToken(String token) {
        int scheme = token.indexOf("://");
        if (scheme < 0) return token;
        String tail = "";
        String body = token;
        while (body.length() > 0) {
            char last = body.charAt(body.length() - 1);
            if (last == '.' || last == ',' || last == ')' || last == ']'
                    || last == ';' || last == ':') {
                tail = last + tail;
                body = body.substring(0, body.length() - 1);
            } else {
                break;
            }
        }
        String host = Routes.hostOf(body);
        if (host.startsWith("www.")) host = host.substring(4);
        return host + tail;
    }

    /** The Listen control's label: mid-speech it is the Stop;
     *  without an engine it says why it is quiet. */
    public static String buttonLabel(boolean available, boolean speaking) {
        if (!available) return "Listen n/a";
        return speaking ? "■ Stop" : "🔊 Listen";
    }

    /** The Listen control is enabled exactly when an engine is
     *  available (a speaking control stays enabled — it is the
     *  way to stop). */
    public static boolean buttonEnabled(boolean available) {
        return available;
    }
}
