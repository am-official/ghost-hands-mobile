package com.ghosthands.app;

import java.io.File;
import java.util.Set;

/**
 * v3.6 (DATA — clear-data actions): the file half of the two
 * wipe buttons, factored onto plain java.io so the harness can
 * pin the scope exactly: what a wipe deletes, and — the point
 * of the exercise — what it never touches.
 *
 * The scope is structural. clearDir deletes regular files
 * DIRECTLY inside the one directory it is handed (no recursion,
 * no siblings, nothing outside it), skipping any name the
 * caller protects. The trails-and-screenshots action hands it
 * exactly two directories: the trails store and the shots
 * store. Model files live in the model store's own directory,
 * settings and the saved-goals / routes stores live in
 * SharedPreferences — none of them is a directory this class is
 * ever given, so none of them can be reached by a wipe.
 *
 * The goals-and-routes action deletes no files at all: the
 * Activity empties the two preference stores (and disarms any
 * scheduled checks) itself.
 */
public final class DataWipe {

    private DataWipe() { }

    /** How many regular files sit directly in a directory
     *  (0 for a null, missing, or unreadable directory). The
     *  confirm dialogs name this number before anything is
     *  deleted. */
    public static int countFiles(File dir) {
        if (dir == null) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int n = 0;
        for (int i = 0; i < files.length; i++) {
            if (files[i].isFile()) n++;
        }
        return n;
    }

    /**
     * Delete every regular file directly inside `dir` except
     * the protected names; returns how many files were actually
     * deleted. Subdirectories and their contents are left alone
     * (the stores are flat; anything nested is not the store's
     * to delete). A null or missing directory deletes nothing.
     */
    public static int clearDir(File dir, Set<String> protectedNames) {
        if (dir == null) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int deleted = 0;
        for (int i = 0; i < files.length; i++) {
            if (!files[i].isFile()) continue;
            if (protectedNames != null
                    && protectedNames.contains(files[i].getName())) {
                continue;
            }
            if (files[i].delete()) deleted++;
        }
        return deleted;
    }
}
