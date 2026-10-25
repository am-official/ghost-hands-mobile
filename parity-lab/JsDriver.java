import com.ghosthands.app.*;
import java.nio.file.*;
import java.util.*;

/** Scratch driver (NOT part of the app): emits the app's exact JS
 *  strings and runs the pure Java layers (parseMap/shapeMap/
 *  decideHeal) over browser-produced JSON, for the v2.7 parity
 *  diagnosis. Modes:
 *    dumpjs <dir>            write MAP_JS etc. to files
 *    shape <mapRawJsonFile>  parse+shape a MAP_JS JSON text (the
 *                            inner JSON string, as decodeJs yields),
 *                            print number/domIndex/name/href lines
 *                            and write stash.js
 *    heal <shapedN> <identityJsonFile> <scanJsonFile>
 *                            run decideHeal for element N using a
 *                            saved shaped map (from shape mode's
 *                            state file) — simplified: see code. */
public class JsDriver {

    static void dumpAll() throws Exception {
        java.io.File dir = new java.io.File("jsall");
        dir.mkdirs();
        java.util.Map<String, String> scripts = new java.util.LinkedHashMap<String, String>();
        scripts.put("map.js", Eyes.MAP_JS);
        scripts.put("identity.js", Eyes.identityJs(3));
        scripts.put("identityScan.js", Eyes.identityScanJs());
        scripts.put("tapScan.js", Eyes.tapScanJs(2, 3));
        scripts.put("typeScan.js", Eyes.typeScanJs(2, 3, "Oakdale"));
        scripts.put("tap.js", Eyes.tapJs(3));
        scripts.put("type.js", Eyes.typeJs(3, "Oakdale"));
        scripts.put("read.js", Eyes.readTextJs(0, Eyes.READ_CHUNK_CHARS));
        scripts.put("scroll.js", Eyes.scrollJs("down"));
        for (java.util.Map.Entry<String, String> e : scripts.entrySet()) {
            java.nio.file.Files.write(java.nio.file.Paths.get("jsall", e.getKey()), e.getValue().getBytes("UTF-8"));
            System.out.println(e.getKey() + " " + e.getValue().length());
        }
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("dumpall")) { dumpAll(); return; }
        if (args[0].equals("dumpjs")) {
            Path dir = Paths.get(args[1]);
            Files.createDirectories(dir);
            Files.write(dir.resolve("map.js"), Eyes.MAP_JS.getBytes("UTF-8"));
            Files.write(dir.resolve("identityScan.js"), Eyes.identityScanJs().getBytes("UTF-8"));
            System.out.println("dumped");
            return;
        }
        if (args[0].equals("shape")) {
            String inner = new String(Files.readAllBytes(Paths.get(args[1])), "UTF-8");
            // parseMap expects the evaluateJavascript-shaped input:
            // an outer JSON string containing the JSON text.
            Eyes.Perception p = Eyes.parseMap(Json.quote(inner));
            StringBuilder sb = new StringBuilder();
            for (ElementInfo e : p.elements) {
                sb.append(e.number).append(" | dom=").append(e.domIndex)
                  .append(" | ").append(e.tag).append(" | ptr=").append(e.pointer)
                  .append(" | name=").append(e.name)
                  .append(" | href=").append(e.href).append("\n");
            }
            System.out.print(sb.toString());
            Files.write(Paths.get(args[2]), Eyes.stashJs(p.elements).getBytes("UTF-8"));
            // state: perceived elements for heal mode (number, identity fields)
            StringBuilder st = new StringBuilder();
            for (ElementInfo e : p.elements) {
                st.append(e.number).append("\t").append(e.tag).append("\t")
                  .append(e.name).append("\t").append(e.href).append("\t")
                  .append(e.type).append("\n");
            }
            Files.write(Paths.get(args[3]), st.toString().getBytes("UTF-8"));
            System.out.println("url=" + p.url + " title=" + p.title + " sig=" + p.signature());
            return;
        }
        if (args[0].equals("identity")) {
            // args: identityJs for N -> print script
            System.out.print(Eyes.identityJs(Integer.parseInt(args[1])));
            return;
        }
        if (args[0].equals("tapscan")) {
            System.out.print(Eyes.tapScanJs(Integer.parseInt(args[1]), Integer.parseInt(args[2])));
            return;
        }
        if (args[0].equals("heal")) {
            // args: heal <stateFile> <n> <atStashJsonFile|NONE> <scanJsonFile|NONE>
            List<String> lines = Files.readAllLines(Paths.get(args[1]));
            int n = Integer.parseInt(args[2]);
            ElementInfo perceived = null;
            for (String ln : lines) {
                String[] f = ln.split("\t", -1);
                if (Integer.parseInt(f[0]) == n) {
                    perceived = new ElementInfo();
                    perceived.number = n; perceived.tag = f[1];
                    perceived.name = f[2]; perceived.href = f[3]; perceived.type = f[4];
                }
            }
            ElementInfo atStash = null;
            if (!args[3].equals("NONE")) {
                String j = new String(Files.readAllBytes(Paths.get(args[3])), "UTF-8");
                atStash = Eyes.parseIdentity(j);
            }
            List<ElementInfo> scan = null;
            if (!args[4].equals("NONE")) {
                String j = new String(Files.readAllBytes(Paths.get(args[4])), "UTF-8");
                scan = Eyes.parseIdentityScan(j);
            }
            Eyes.Heal h = Eyes.decideHeal(perceived, atStash, scan);
            System.out.println("heal kind=" + h.kind + " scanIndex=" + h.scanIndex
                + " atStash=" + (atStash == null ? "null" : atStash.tag + "/" + atStash.name + "/" + atStash.href)
                + " scanSize=" + (scan == null ? -1 : scan.size()));
            if (scan != null) {
                for (int i = 0; i < scan.size(); i++) {
                    ElementInfo s = scan.get(i);
                    if (Eyes.identityMatches(perceived, s)) {
                        System.out.println("  scan match at " + i + ": " + s.tag + " " + s.name);
                    }
                }
            }
            return;
        }
        System.out.println("unknown mode");
    }
}
