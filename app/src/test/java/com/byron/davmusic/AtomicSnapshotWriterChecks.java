package com.byron.davmusic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Real-filesystem checks shared by JUnit and the SDK-free main harness. */
public final class AtomicSnapshotWriterChecks {
    private AtomicSnapshotWriterChecks() {}

    public static void check(String scenario) throws Exception {
        Path dir = Files.createTempDirectory("snapshot-check-");
        Path target = dir.resolve("目录.json");
        try {
            switch (scenario) {
                case "new":
                    AtomicSnapshotWriter.write(target.toFile(), "{\"files\":[\"中文 🎵\"]}");
                    equal("{\"files\":[\"中文 🎵\"]}", read(target));
                    onlyTarget(dir, target);
                    break;
                case "replace":
                    Files.write(target, bytes("old-long-snapshot"));
                    AtomicSnapshotWriter.write(target.toFile(), "新");
                    equal("新", read(target));
                    onlyTarget(dir, target);
                    break;
                case "failedOverwrite":
                case "failedFirst":
                    boolean existed = scenario.equals("failedOverwrite");
                    if (existed) Files.write(target, bytes("old"));
                    try {
                        AtomicSnapshotWriter.write(target.toFile(), out -> {
                            out.write(bytes("{partial"));
                            throw new IOException("injected write failure");
                        });
                        throw new AssertionError("IOException must escape");
                    } catch (IOException expected) {
                        equal("injected write failure", expected.getMessage());
                    }
                    if (existed) {
                        equal("old", read(target));
                        onlyTarget(dir, target);
                    } else {
                        require(!Files.exists(target), "failed first save published a target");
                        try (Stream<Path> paths = Files.list(dir)) {
                            require(paths.count() == 0, "failed save left temporary files");
                        }
                    }
                    break;
                case "beforePublish":
                    Files.write(target, bytes("old"));
                    AtomicSnapshotWriter.write(target.toFile(), out -> {
                        out.write(bytes("new"));
                        equal("old", read(target));
                    });
                    equal("new", read(target));
                    onlyTarget(dir, target);
                    break;
                case "moveFailure":
                    Files.createDirectory(target);
                    Files.write(target.resolve("keep"), bytes("old"));
                    try {
                        AtomicSnapshotWriter.write(target.toFile(), "new");
                        throw new AssertionError("replacing a nonempty directory must fail");
                    } catch (IOException expected) {
                        equal("old", read(target.resolve("keep")));
                        onlyTarget(dir, target);
                    }
                    break;
                case "concurrent":
                    concurrent(target);
                    onlyTarget(dir, target);
                    break;
                default: throw new AssertionError("unknown scenario " + scenario);
            }
        } finally {
            try (Stream<Path> paths = Files.walk(dir)) {
                Path[] all = paths.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new);
                for (Path path : all) Files.deleteIfExists(path);
            }
        }
    }

    private static void concurrent(Path target) throws Exception {
        String[] values = {"{\"files\":[]}", "{\"files\":[\"" + "中文🎵".repeat(4096) + "\"]}",
                "{\"files\":[\"other\"]}"};
        AtomicSnapshotWriter.write(target.toFile(), values[0]);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> jobs = new ArrayList<>();
        try {
            for (int worker = 0; worker < 6; worker++) {
                final int id = worker;
                jobs.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 150; i++) {
                        if (id < 3) AtomicSnapshotWriter.write(target.toFile(), values[(i + id) % values.length]);
                        else require(Arrays.asList(values).contains(read(target)), "reader saw partial/mixed data");
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> job : jobs) job.get(30, TimeUnit.SECONDS);
            require(Arrays.asList(values).contains(read(target)), "invalid final data");
        } finally {
            pool.shutdownNow();
            require(pool.awaitTermination(30, TimeUnit.SECONDS), "workers did not stop");
        }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
    private static void onlyTarget(Path dir, Path target) throws IOException {
        try (Stream<Path> paths = Files.list(dir)) {
            Path[] entries = paths.toArray(Path[]::new);
            require(entries.length == 1 && entries[0].equals(target), "temporary file leaked");
        }
    }
    private static void equal(String expected, String actual) {
        require(expected.equals(actual), "expected <" + expected + "> but was <" + actual + ">");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        String[] scenarios = args.length == 0
                ? new String[]{"new", "replace", "failedOverwrite", "failedFirst", "beforePublish", "moveFailure", "concurrent"}
                : args;
        for (String scenario : scenarios) {
            check(scenario);
            System.out.println("PASS " + scenario);
        }
        System.out.println("PASS " + scenarios.length + " scenarios");
    }
}
