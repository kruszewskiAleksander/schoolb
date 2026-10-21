import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class SchoolBackupDaemonTest {
    static int assertions;

    static void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    interface CheckedRunnable {
        void run() throws Exception;
    }

    static void rejects(CheckedRunnable action, Class<? extends Exception> type, String message) throws Exception {
        try {
            action.run();
        } catch (Exception e) {
            check(type.isInstance(e), message + ": " + e);
            return;
        }
        throw new AssertionError("Nie odrzucono: " + message);
    }

    record Attributes(long size, FileTime lastModifiedTime, FileTime creationTime, Object fileKey) implements BasicFileAttributes {
        public FileTime lastAccessTime() { return lastModifiedTime; }
        public boolean isRegularFile() { return true; }
        public boolean isDirectory() { return false; }
        public boolean isSymbolicLink() { return false; }
        public boolean isOther() { return false; }
    }

    static void configAndPaths(Path root) throws Exception {
        String source = root.resolve("exchange").toString();
        String archive = root.resolve(".archive").toString();
        String reports = root.resolve(".reports").toString();
        SchoolBackupDaemon.Config config = SchoolBackupDaemon.Config.parse(new String[]{source, archive, reports, "1"});
        check(config.reportSeconds() == 1, "Interwał raportu");
        check(config.source().isAbsolute(), "Ścieżki absolutne");
        rejects(() -> SchoolBackupDaemon.Config.parse(new String[]{source, source, reports}), IllegalArgumentException.class, "Tożsame katalogi");
        rejects(() -> SchoolBackupDaemon.Config.parse(new String[]{source, Path.of(source).resolve(".archive").toString(), reports}), IllegalArgumentException.class, "Zagnieżdżenie archiwum w wymianie");
        rejects(() -> SchoolBackupDaemon.Config.parse(new String[]{root.resolve(".archive/x").toString(), archive, reports}), IllegalArgumentException.class, "Zagnieżdżenie wymiany w archiwum");
        rejects(() -> SchoolBackupDaemon.Config.parse(new String[]{source, root.resolve("visible").toString(), reports}), IllegalArgumentException.class, "Jawna nazwa archiwum");
        rejects(() -> SchoolBackupDaemon.Config.parse(new String[]{source, archive, root.resolve("visible").toString()}), IllegalArgumentException.class, "Jawna nazwa raportów");
        rejects(() -> SchoolBackupDaemon.Config.parse(new String[]{source, archive, reports, "0"}), IllegalArgumentException.class, "Zerowy interwał");
        rejects(() -> SchoolBackupDaemon.Config.parse(new String[]{source, archive, reports, "bad"}), NumberFormatException.class, "Niepoprawna liczba");
        rejects(() -> SchoolBackupDaemon.Config.parse(new String[]{"a", "b", "c", "1", "extra"}), IllegalArgumentException.class, "Nadmiar argumentów");
        check(!SchoolBackupDaemon.validRelative(Path.of("")), "Pusta nazwa");
        check(!SchoolBackupDaemon.validRelative(root), "Absolutna nazwa");
        check(!SchoolBackupDaemon.validRelative(Path.of("../secret")), "Wyjście poza katalog");
        check(!SchoolBackupDaemon.validRelative(Path.of("a/../secret")), "Nienormalizowana ścieżka");
        check(SchoolBackupDaemon.validRelative(Path.of("uczen/projekt.txt")), "Normalna ścieżka");
        check(SchoolBackupDaemon.validRelative(Path.of(".hidden.txt")), "Ukryty plik źródłowy");
        String saved = System.getProperty("backup.workers");
        try {
            System.setProperty("backup.workers", "0");
            rejects(() -> SchoolBackupDaemon.Config.parse(new String[]{source, archive, reports}), IllegalArgumentException.class, "Brak wątków");
        } finally {
            if (saved == null) System.clearProperty("backup.workers"); else System.setProperty("backup.workers", saved);
        }
    }

    static void unicodeAndReports(Path root) {
        String unicode = "zażółć😀".repeat(100);
        String shortened = SchoolBackupDaemon.shorten(unicode, 160);
        check(shortened.getBytes(StandardCharsets.UTF_8).length <= 160, "Limit nazwy w bajtach");
        check(unicode.startsWith(shortened), "Bez zmiany tekstu");
        check(!Character.isHighSurrogate(shortened.charAt(shortened.length() - 1)), "Brak urwanego znaku Unicode");
        check(SchoolBackupDaemon.shorten("abc", 160).equals("abc"), "Krótka nazwa");
        check(SchoolBackupDaemon.shorten("😀", 3).isEmpty(), "Całe punkty kodowe");
        check(SchoolBackupDaemon.shorten("😀", 4).equals("😀"), "Granica czterech bajtów");
        check(SchoolBackupDaemon.ReportGenerator.escape("<&>\"'").equals("&lt;&amp;&gt;&quot;&#39;"), "Kodowanie HTML");
        check(SchoolBackupDaemon.ReportGenerator.mib(1_048_576).equals("1,00"), "Jednostki MiB");
        SchoolBackupDaemon.Statistics stats = new SchoolBackupDaemon.Statistics();
        long total = 0;
        for (int i = 0; i < 100; i++) {
            long size = i * 10L;
            total += size;
            FileTime modified = FileTime.fromMillis(i * 1000L);
            stats.add(root.resolve("file-" + i), new Attributes(size, modified, modified, i), "Wymiana");
        }
        check(stats.count == 100 && stats.bytes == total, "Sumy raportu");
        List<SchoolBackupDaemon.Row> top = SchoolBackupDaemon.Statistics.descending(stats.largest);
        check(top.size() == 5 && top.get(0).size() == 990 && top.get(4).size() == 950, "TOP 5 i sortowanie");
        List<SchoolBackupDaemon.Row> recent = SchoolBackupDaemon.Statistics.descending(stats.recent);
        check(recent.size() == 20 && recent.get(0).path().getFileName().toString().equals("file-99"), "20 najnowszych");
        FileTime old = FileTime.fromMillis(0);
        FileTime added = FileTime.fromMillis(200_000);
        stats.add(root.resolve("uploaded-old-file"), new Attributes(0, old, added, 100), "Wymiana");
        check(SchoolBackupDaemon.Statistics.descending(stats.recent).get(0).activity().equals(added), "Nowy plik ze starą datą modyfikacji");
        SchoolBackupDaemon.Version version = SchoolBackupDaemon.Version.of(new Attributes(10, old, old, "inode-1"));
        check(version.equals(SchoolBackupDaemon.Version.of(new Attributes(10, old, added, "inode-1"))), "Tożsamość wersji");
        check(!version.equals(SchoolBackupDaemon.Version.of(new Attributes(10, old, old, "inode-2"))), "Wymiana inode");
        check(!version.equals(SchoolBackupDaemon.Version.of(new Attributes(11, old, old, "inode-1"))), "Zmiana rozmiaru");
        check(!version.equals(SchoolBackupDaemon.Version.of(new Attributes(10, added, old, "inode-1"))), "Zmiana daty");
        SchoolBackupDaemon.Config config = SchoolBackupDaemon.Config.parse(new String[]{root.resolve("exchange").toString(), root.resolve(".archive").toString(), root.resolve(".reports").toString()});
        SchoolBackupDaemon.ReportGenerator renderer = new SchoolBackupDaemon.ReportGenerator(config, null, null);
        String rendered = renderer.table(List.of(new SchoolBackupDaemon.Row(root.resolve("a&b.txt"), 10, old, added, "<script>alert(1)</script>")), true);
        check(rendered.contains("a&amp;b.txt") && !rendered.contains("<script>"), "Brak HTML injection w tabeli");
        check(rendered.contains("&lt;script&gt;"), "Escaping wszystkich pól tabeli");
        check(renderer.table(List.of(), false).contains("Brak plików"), "Pusty raport");
    }

    static void event(SchoolBackupDaemon.FileWatcher watcher, WatchEvent.Kind<Path> expected, Path filename) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            WatchKey key = watcher.service.poll(500, TimeUnit.MILLISECONDS);
            if (key == null) continue;
            boolean found = false;
            for (WatchEvent<?> e : key.pollEvents()) {
                if (e.kind() == expected && filename.equals(e.context())) found = true;
            }
            check(watcher.directoryFor(key) != null, "Mapowanie klucza WatchService");
            key.reset();
            if (found) return;
        }
        throw new AssertionError("Brak zdarzenia " + expected + ": " + filename);
    }

    static void watcher(Path root) throws Exception {
        Path exchange = Files.createDirectory(root.resolve("watcher"));
        try (SchoolBackupDaemon.FileWatcher watcher = new SchoolBackupDaemon.FileWatcher(exchange)) {
            BasicFileAttributes attributes = Files.readAttributes(exchange, BasicFileAttributes.class);
            watcher.register(new SchoolBackupDaemon.Entry(Path.of(""), attributes));
            watcher.register(new SchoolBackupDaemon.Entry(Path.of(""), attributes));
            check(watcher.directories.size() == 1, "Brak podwójnych obserwatorów");
            Path file = exchange.resolve("plik.txt");
            Files.writeString(file, "pierwsza wersja");
            event(watcher, StandardWatchEventKinds.ENTRY_CREATE, file.getFileName());
            Files.writeString(file, "druga wersja");
            event(watcher, StandardWatchEventKinds.ENTRY_MODIFY, file.getFileName());
            Files.delete(file);
            event(watcher, StandardWatchEventKinds.ENTRY_DELETE, file.getFileName());
            Path nested = Files.createDirectory(exchange.resolve("uczen"));
            event(watcher, StandardWatchEventKinds.ENTRY_CREATE, nested.getFileName());
            watcher.register(new SchoolBackupDaemon.Entry(Path.of("uczen"), Files.readAttributes(nested, BasicFileAttributes.class)));
            Files.writeString(nested.resolve("nested.txt"), "dane");
            event(watcher, StandardWatchEventKinds.ENTRY_CREATE, Path.of("nested.txt"));
            check(watcher.directories.size() == 2, "Obserwacja podkatalogu");
        }
    }

    static List<Path> snapshots(Path archive) {
        Path data = archive.resolve(".snapshots");
        if (!Files.exists(data)) return List.of();
        try (var paths = Files.walk(data)) {
            return paths.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) { check(true, message); return; }
            Thread.sleep(50);
        }
        throw new AssertionError("Timeout: " + message);
    }

    static boolean hasContent(Path archive, String content) {
        return snapshots(archive).stream().anyMatch(path -> {
            try { return Files.readString(path).equals(content); } catch (IOException e) { return false; }
        });
    }

    static void linuxIntegration(Path root) throws Exception {
        Path source = Files.createDirectory(root.resolve("exchange"));
        Path archive = root.resolve(".archive");
        Path reports = root.resolve(".reports");
        Files.writeString(source.resolve("initial.txt"), "initial");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-Dbackup.reserveMiB=0", "-Dbackup.rescanSeconds=1", "-cp",
                System.getProperty("java.class.path"), "SchoolBackupDaemon", source.toString(), archive.toString(), reports.toString(), "1")
                .inheritIO().start();
        try {
            await(() -> hasContent(archive, "initial"), "Kopia początkowa");
            Path nested = Files.createDirectories(source.resolve("klasa/uczen"));
            Path file = nested.resolve("zadanie.txt");
            Files.writeString(file, "v1");
            await(() -> hasContent(archive, "v1"), "Nowy plik w zagnieżdżonym katalogu");
            Files.writeString(file, "v2");
            await(() -> hasContent(archive, "v2"), "Nowa wersja");
            check(hasContent(archive, "v1"), "Zachowanie historii");
            StringBuilder uploaded = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                uploaded.append("chunk-").append(i).append('\n');
                Files.writeString(file, uploaded);
                Thread.sleep(100);
            }
            await(() -> hasContent(archive, uploaded.toString()), "Stabilizacja transferu porcjami");
            Path other = Files.createDirectories(source.resolve("inna-klasa"));
            Files.writeString(other.resolve("zadanie.txt"), "same-name-other-folder");
            await(() -> hasContent(archive, "same-name-other-folder"), "Te same nazwy w różnych katalogach");
            Path empty = source.resolve("empty.txt");
            Files.createFile(empty);
            await(() -> snapshots(archive).stream().anyMatch(p -> p.getFileName().toString().endsWith("_empty.txt")), "Pusty plik");
            String hostile = "<script>alert(1)</script>";
            Files.writeString(source.resolve("<img onerror=alert(1)>.txt"), "html-name");
            Path external = Files.writeString(root.resolve("secret.txt"), hostile);
            Files.createSymbolicLink(source.resolve("forbidden.txt"), external);
            Files.createSymbolicLink(source.resolve("forbidden-directory"), root);
            await(() -> hasContent(archive, "html-name"), "Nazwa ze znacznikami HTML");
            check(!hasContent(archive, hostile), "Pominięcie dowiązań do plików i katalogów");
            Path timed = source.resolve("atime.txt");
            Files.writeString(timed, "atime-content");
            FileTime originalAtime = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"));
            Files.getFileAttributeView(timed, BasicFileAttributeView.class).setTimes(null, originalAtime, null);
            FileTime originalMtime = Files.getLastModifiedTime(timed);
            await(() -> hasContent(archive, "atime-content"), "Kopia z przywracaniem atime");
            await(() -> {
                try { return Files.readAttributes(timed, BasicFileAttributes.class).lastAccessTime().equals(originalAtime); }
                catch (IOException e) { return false; }
            }, "Przywrócony czas dostępu");
            check(Files.getLastModifiedTime(timed).equals(originalMtime), "Mtime źródła bez zmian");
            int versionCount = snapshots(archive).size();
            Thread.sleep(2500);
            check(snapshots(archive).size() == versionCount, "Brak pętli backupów po aktualizacji atime");
            Files.delete(timed);
            check(hasContent(archive, "atime-content"), "Usunięcie źródła zachowuje archiwum");
            for (Path snapshot : snapshots(archive)) {
                if (Files.readString(snapshot).equals("initial")) Files.delete(snapshot);
            }
            await(() -> hasContent(archive, "initial"), "Odtworzenie brakującej kopii podczas uzgadniania");
            Path report = reports.resolve("raport_dzienny.html");
            await(() -> {
                try { return Files.readString(report).contains("&lt;img onerror=alert(1)&gt;.txt"); } catch (IOException e) { return false; }
            }, "Raport HTML z bezpiecznymi nazwami");
            check(Files.getPosixFilePermissions(archive).equals(SchoolBackupDaemon.DIRECTORY_MODE), "Archiwum 0700");
            check(Files.getPosixFilePermissions(reports).equals(SchoolBackupDaemon.DIRECTORY_MODE), "Raporty 0700");
            check(Files.getPosixFilePermissions(report).equals(SchoolBackupDaemon.FILE_MODE), "Raport 0600");
            for (Path snapshot : snapshots(archive)) {
                check(Files.getPosixFilePermissions(snapshot).equals(SchoolBackupDaemon.FILE_MODE), "Kopia 0600");
            }
            try (var paths = Files.walk(source)) {
                check(paths.noneMatch(p -> p.getFileName().toString().endsWith(".log")), "Brak logów w wymianie");
            }
            Process duplicate = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "SchoolBackupDaemon",
                    source.toString(), archive.toString(), reports.toString(), "1").inheritIO().start();
            check(duplicate.waitFor(5, TimeUnit.SECONDS) && duplicate.exitValue() != 0, "Blokada drugiej instancji");
            if (duplicate.isAlive()) duplicate.destroyForcibly();
        } finally {
            process.destroy();
            if (!process.waitFor(25, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor();
            }
        }
    }

    static void unsupportedPlatform(Path root) throws Exception {
        Path source = Files.createDirectory(root.resolve("unsupported"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "SchoolBackupDaemon", source.toString(),
                root.resolve(".unsupported-archive").toString(), root.resolve(".unsupported-reports").toString()).redirectErrorStream(true).start();
        check(process.waitFor(5, TimeUnit.SECONDS), "Szybkie odrzucenie nieobsługiwanej platformy");
        check(process.exitValue() != 0, "Kod błędu nieobsługiwanej platformy");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        check(output.contains("POSIX"), "Czytelna diagnoza platformy");
        check(!Files.exists(root.resolve(".unsupported-archive")), "Brak niezabezpieczonego archiwum na Windows");
        rejects(() -> new SchoolBackupDaemon.SourceTree(source), IOException.class, "Wymagany SecureDirectoryStream");
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(".").toAbsolutePath(), "daemon-tests-");
        try {
            configAndPaths(root);
            unicodeAndReports(root);
            watcher(root);
            if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux")) {
                linuxIntegration(root);
            } else {
                unsupportedPlatform(root);
                System.out.println("SKIPPED: Linux integration (POSIX, SecureDirectoryStream, snapshots, atime, inotify).");
            }
            System.out.println("PASS: " + assertions + " assertions.");
        } finally {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                    if (error != null) throw error;
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }
}
