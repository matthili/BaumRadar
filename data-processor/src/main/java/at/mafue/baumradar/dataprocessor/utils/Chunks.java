package at.mafue.baumradar.dataprocessor.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Aufteilen großer Publish-Artefakte in nummerierte 50-MB-Chunks
 * ({@code name.001}, {@code name.002}, …) für GitHub Pages/CDNs — und das
 * Aufräumen veralteter Chunks, wenn eine Datei wieder klein genug ist.
 * Wird vom Baum-Publish ({@link at.mafue.baumradar.dataprocessor.Pipeline})
 * und vom Geocoder-Cutter gleichermaßen genutzt.
 */
public final class Chunks {

    /** GitHub Pages und viele CDNs mögen keine Dateien über 50 MB. */
    public static final long MAX_SIZE = 50L * 1024 * 1024;

    private Chunks() {
    }

    /**
     * Zerteilt {@code file} in nummerierte Chunks im selben Verzeichnis, wenn es
     * {@link #MAX_SIZE} überschreitet (das Original wird danach gelöscht, damit es
     * nicht versehentlich committet wird). Ist die Datei klein genug, werden
     * stattdessen eventuell vorhandene alte Chunks eines früheren Laufs entfernt.
     *
     * @return Anzahl der erzeugten Chunks (0 = Datei blieb ungeteilt)
     */
    public static int splitIfNeeded(File file) throws IOException {
        File dir = file.getParentFile();
        if (file.length() <= MAX_SIZE) {
            deleteStaleChunks(dir, file.getName());
            return 0;
        }
        int chunkIndex = 1;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int len;
            long currentChunkSize = 0;
            FileOutputStream chunkFos = null;
            try {
                while ((len = fis.read(buffer)) > 0) {
                    if (chunkFos == null) {
                        File chunkFile = new File(dir, String.format("%s.%03d", file.getName(), chunkIndex));
                        chunkFos = new FileOutputStream(chunkFile);
                        currentChunkSize = 0;
                    }
                    chunkFos.write(buffer, 0, len);
                    currentChunkSize += len;
                    if (currentChunkSize >= MAX_SIZE) {
                        chunkFos.close();
                        chunkFos = null;
                        chunkIndex++;
                    }
                }
            } finally {
                if (chunkFos != null) chunkFos.close();
            }
        }
        // Ältere, überzählige Chunks eines früheren (größeren) Laufs entfernen.
        for (int i = chunkIndex + 1; i < 100; i++) {
            File old = new File(dir, String.format("%s.%03d", file.getName(), i));
            if (old.exists()) old.delete(); else break;
        }
        file.delete();
        return chunkIndex;
    }

    /** Entfernt alle nummerierten Chunks zu {@code baseName} (Lauf wurde wieder klein). */
    private static void deleteStaleChunks(File dir, String baseName) {
        for (int i = 1; i < 100; i++) {
            File old = new File(dir, String.format("%s.%03d", baseName, i));
            if (old.exists()) old.delete(); else break;
        }
    }
}
