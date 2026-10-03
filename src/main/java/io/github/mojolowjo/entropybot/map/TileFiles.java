package io.github.mojolowjo.entropybot.map;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The terrain map's files under {@code <instance>/minecraft/entropybot/map}: one PNG per region at
 * {@code <dim folder>/<rx>.<rz>.png} and {@code index.json} listing them. Every write goes to a temp file
 * first and is then moved over the old one, so the dashboard never reads half a file. Runs on the map's
 * background thread; no Minecraft classes.
 */
public final class TileFiles {
    public static final String INDEX = "index.json";

    private TileFiles() {}

    /** A region's pixels (512x512 ARGB) as a PNG, written atomically. */
    public static void writePng(Path file, int[] px) throws IOException {
        int n = MapMath.REGION;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, n, n, px, 0, n);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                if (!ImageIO.write(img, "png", out)) throw new IOException("no PNG writer");
            }
            move(tmp, file);
        } catch (IOException | RuntimeException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw e;
        }
    }

    /**
     * A region's pixels from its PNG; null when there is no file. A file that is no 512x512 image is
     * moved aside to {@code <rx>.<rz>.broken.png} (the region then starts over) and gives null too.
     */
    public static int[] readPng(Path file) throws IOException {
        if (!Files.exists(file)) return null;
        int n = MapMath.REGION;
        BufferedImage img;
        try {
            img = ImageIO.read(file.toFile());
        } catch (IOException e) {
            img = null;
        }
        if (img == null || img.getWidth() != n || img.getHeight() != n) {
            String name = file.getFileName().toString();
            move(file, file.resolveSibling(name.substring(0, name.length() - 4) + ".broken.png"));
            return null;
        }
        return img.getRGB(0, 0, n, n, null, 0, n);
    }

    /**
     * Rewrites index.json from the PNGs on disk: {"<dim>":[[rx,rz,mtimeMs],...]}. The dimension ids come
     * from {@code dims} (this session's), then the old index's keys, else a guess from the folder name.
     * Returns how many tiles it listed.
     */
    public static int writeIndex(Path root, Collection<String> dims) throws IOException {
        Map<String, String> names = new HashMap<>();
        Path index = root.resolve(INDEX);
        if (Files.exists(index)) {
            try {
                JsonElement old = JsonParser.parseString(Files.readString(index, StandardCharsets.UTF_8));
                if (old.isJsonObject()) for (String k : old.getAsJsonObject().keySet()) names.put(MapMath.dimFolder(k), k);
            } catch (RuntimeException ignored) {
                // a broken index is simply rebuilt
            }
        }
        for (String d : dims) names.put(MapMath.dimFolder(d), d);
        Map<String, List<long[]>> out = new TreeMap<>();
        int count = 0;
        if (Files.isDirectory(root)) {
            try (DirectoryStream<Path> folders = Files.newDirectoryStream(root, Files::isDirectory)) {
                for (Path folder : folders) {
                    String f = folder.getFileName().toString();
                    List<long[]> tiles = new ArrayList<>();
                    try (DirectoryStream<Path> pngs = Files.newDirectoryStream(folder, "*.png")) {
                        for (Path p : pngs) {
                            int[] r = MapMath.parseFileName(p.getFileName().toString());
                            if (r == null) continue;
                            tiles.add(new long[] { r[0], r[1], Files.getLastModifiedTime(p).toMillis() });
                        }
                    }
                    if (tiles.isEmpty()) continue;
                    tiles.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
                    out.put(names.getOrDefault(f, MapMath.guessDim(f)), tiles);
                    count += tiles.size();
                }
            }
        }
        Files.createDirectories(root);
        Path tmp = root.resolve(INDEX + ".tmp");
        Files.writeString(tmp, MapMath.indexJson(out), StandardCharsets.UTF_8);
        move(tmp, index);
        return count;
    }

    // Windows refuses the move while another program holds the old file open without sharing it
    // (a virus scanner, a backup): a few short retries before giving up.
    static void move(Path from, Path to) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                try {
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (AccessDeniedException e) {
                if (attempt >= 4) throw e;
                try {
                    Thread.sleep(50L << attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
