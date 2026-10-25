import java.nio.file.Files;
import java.nio.file.Path;

/** Lab tool: dump the app's emitted JS strings (production text)
 *  to files so node --check and the jsdom lab can exercise the
 *  exact bytes the phone will run. */
public class DumpJs {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Files.createDirectories(dir);
        write(dir, "map.js", com.ghosthands.app.Eyes.MAP_JS);
        write(dir, "read.js", com.ghosthands.app.Eyes.readTextJs(0, 2400));
        write(dir, "read-candidates.js",
                com.ghosthands.app.Eyes.readCandidatesJs());
        write(dir, "probe.js", com.ghosthands.app.Eyes.pageTextLengthJs());
        write(dir, "read-at-cand.js",
                com.ghosthands.app.Eyes.readTextAtJs(2, 0, 2400));
        write(dir, "read-at-full.js",
                com.ghosthands.app.Eyes.readTextAtJs(-1, 0, 2400));
        write(dir, "preview-at.js", com.ghosthands.app.Eyes.previewAtJs(2));
        write(dir, "identity.js", com.ghosthands.app.Eyes.identityJs(3));
        write(dir, "identity-scan.js",
                com.ghosthands.app.Eyes.identityScanJs());
        write(dir, "tap.js", com.ghosthands.app.Eyes.tapJs(1));
        write(dir, "tap-scan.js", com.ghosthands.app.Eyes.tapScanJs(2, 3));
        System.out.println("dumped to " + dir);
    }

    private static void write(Path dir, String name, String js)
            throws Exception {
        Files.writeString(dir.resolve(name), js);
    }
}
