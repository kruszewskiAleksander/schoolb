import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class SchoolBackupDaemon implements AutoCloseable {
    static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};
    static final Set<PosixFilePermission> DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------");
    static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");
    static final FileAttribute<Set<PosixFilePermission>> DIRECTORY_ATTRIBUTE = PosixFilePermissions.asFileAttribute(DIRECTORY_MODE);
    static final FileAttribute<Set<PosixFilePermission>> FILE_ATTRIBUTE = PosixFilePermissions.asFileAttribute(FILE_MODE);
    static final Path EMPTY = Path.of("");
    static final long QUIET_MILLIS = 1_000;
    static final int MAX_DEPTH = 128;

    record Config(Path source, Path archive, Path reports, long reportSeconds, long rescanSeconds,
                  int workers, long reserveBytes, ZoneId zone) {
        static Config parse(String[] args) {
            if (args.length > 4) {
                throw new IllegalArgumentException("Użycie: java SchoolBackupDaemon [wymiana [archiwum [raporty [interwał_raportu_s]]]]");
            }
            Config c = new Config(
                    absolute(args.length > 0 ? args[0] : "/home/serwer/szkola_wymiana"),
                    absolute(args.length > 1 ? args[1] : "/home/serwer/.backup_archiwum"),
                    absolute(args.length > 2 ? args[2] : "/home/serwer/.raporty"),
                    Long.parseLong(args.length > 3 ? args[3] : "3600"),
                    Long.parseLong(System.getProperty("backup.rescanSeconds", "300")),
                    Integer.parseInt(System.getProperty("backup.workers", "2")),
                    Math.multiplyExact(Long.parseLong(System.getProperty("backup.reserveMiB", "256")), 1_048_576L),
                    ZoneId.of(System.getProperty("backup.zone", "Europe/Warsaw")));
            if (c.reportSeconds < 1 || c.rescanSeconds < 1 || c.workers < 1 || c.workers > 32 || c.reserveBytes < 0) {
                throw new IllegalArgumentException("Nieprawidłowy interwał, liczba wątków lub rezerwa dysku.");
            }
            List<Path> paths = List.of(c.source, c.archive, c.reports);
            for (int i = 0; i < paths.size(); i++) {
                for (int j = i + 1; j < paths.size(); j++) {
                    if (paths.get(i).startsWith(paths.get(j)) || paths.get(j).startsWith(paths.get(i))) {
                        throw new IllegalArgumentException("Katalogi wymiany, archiwum i raportów muszą być rozłączne.");
                    }
                }
            }
            for (Path p : List.of(c.archive, c.reports)) {
                if (p.getFileName() == null || !p.getFileName().toString().startsWith(".")) {
                    throw new IllegalArgumentException("Archiwum i raporty wymagają nazw katalogów rozpoczynających się kropką.");
                }
            }
            return c;
        }
    }

    record Version(String key, long size, FileTime modified) {
        static Version of(BasicFileAttributes a) {
            return new Version(Objects.toString(a.fileKey(), ""), a.size(), a.lastModifiedTime());
        }
    }

    record Entry(Path relative, BasicFileAttributes attributes) { }
    record Saved(Version version, Path snapshot) { }
    record Row(Path path, long size, FileTime modified, FileTime activity, String area) { }

    static Path absolute(String value) {
        return Path.of(value).toAbsolutePath().normalize();
    }

    static void log(String level, String text, Throwable error) {
        String message = Instant.now() + " [" + level + "] " + text;
        if (error != null) {
            message += " | " + error.getClass().getSimpleName() + ": " + error.getMessage();
        }
        message = message.replace('\n', ' ').replace('\r', ' ').replace('\u001b', ' ');
        if (level.equals("ERROR") || level.equals("WARN")) {
            System.err.println(message);
        } else {
            System.out.println(message);
        }
    }

    static ThreadFactory threadFactory(String prefix) {
        return new ThreadFactory() {
            private int sequence;
            public synchronized Thread newThread(Runnable task) {
                Thread thread = new Thread(task, prefix + "-" + ++sequence);
                thread.setDaemon(true);
                thread.setUncaughtExceptionHandler((t, e) -> log("ERROR", t.getName(), e));
                return thread;
            }
        };
    }

    static void rejectSymlinkComponents(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path name : path) {
            current = current.resolve(name);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Dowiązanie symboliczne w ścieżce: " + current);
            }
        }
    }

    static void privateDirectory(Path path, UserPrincipal owner) throws IOException {
        rejectSymlinkComponents(path);
        Files.createDirectories(path, DIRECTORY_ATTRIBUTE);
        PosixFileAttributes attributes = Files.readAttributes(path, PosixFileAttributes.class, NOFOLLOW);
        if (!attributes.isDirectory() || !attributes.owner().equals(owner)) {
            throw new IOException("Nieprawidłowy właściciel lub typ prywatnego katalogu: " + path);
        }
        Files.setPosixFilePermissions(path, DIRECTORY_MODE);
    }

    static boolean validRelative(Path path) {
        if (path.isAbsolute() || path.toString().isEmpty() || !path.normalize().equals(path)) {
            return false;
        }
        for (Path component : path) {
            if (component.toString().equals("..") || component.toString().equals(".")) {
                return false;
            }
        }
        return true;
    }

    static String shorten(String text, int maxBytes) {
        StringBuilder result = new StringBuilder();
        int length = 0;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            String next = new String(Character.toChars(cp));
            int bytes = next.getBytes(StandardCharsets.UTF_8).length;
            if (length + bytes > maxBytes) {
                break;
            }
            result.append(next);
            length += bytes;
            offset += Character.charCount(cp);
        }
        return result.toString();
    }

    static void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    static final class InstanceLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        InstanceLock(Path directory) throws IOException {
            Path path = directory.resolve(".daemon.lock");
            channel = FileChannel.open(path, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS), FILE_ATTRIBUTE);
            try {
                Files.setPosixFilePermissions(path, FILE_MODE);
                lock = channel.tryLock();
                if (lock == null) {
                    throw new IOException("Inna instancja już korzysta z archiwum.");
                }
            } catch (IOException | RuntimeException e) {
                channel.close();
                throw e;
            }
        }

        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }

    static final class SourceTree implements AutoCloseable {
        final Path root;
        final SecureDirectoryStream<Path> anchor;
        final Object rootKey;

        SourceTree(Path root) throws IOException {
            this.root = root;
            DirectoryStream<Path> stream = Files.newDirectoryStream(root);
            if (!(stream instanceof SecureDirectoryStream<?>)) {
                stream.close();
                throw new IOException("System plików musi obsługiwać SecureDirectoryStream.");
            }
            @SuppressWarnings("unchecked")
            SecureDirectoryStream<Path> secure = (SecureDirectoryStream<Path>) stream;
            anchor = secure;
            try {
                rootKey = anchor.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey();
                verifyRoot();
            } catch (IOException | RuntimeException e) {
                anchor.close();
                throw e;
            }
        }

        void verifyRoot() throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(root, BasicFileAttributes.class, NOFOLLOW);
            if (!attributes.isDirectory() || !Objects.equals(rootKey, attributes.fileKey())) {
                throw new IOException("Katalog wymiany został podmieniony; wymagany restart usługi.");
            }
        }

        SecureDirectoryStream<Path> directory(Path relative) throws IOException {
            if (!relative.toString().isEmpty() && !validRelative(relative)) {
                throw new IOException("Nieprawidłowa ścieżka względna: " + relative);
            }
            SecureDirectoryStream<Path> current = anchor.newDirectoryStream(Path.of("."), NOFOLLOW);
            try {
                if (!relative.toString().isEmpty()) {
                    for (Path name : relative) {
                        SecureDirectoryStream<Path> next = current.newDirectoryStream(name, NOFOLLOW);
                        try {
                            current.close();
                        } catch (IOException e) {
                            next.close();
                            throw e;
                        }
                        current = next;
                    }
                }
                return current;
            } catch (IOException | RuntimeException e) {
                current.close();
                throw e;
            }
        }

        BasicFileAttributes attributes(Path relative) throws IOException {
            if (!validRelative(relative)) {
                throw new IOException("Nieprawidłowa ścieżka: " + relative);
            }
            try (SecureDirectoryStream<Path> parent = directory(relative.getParent() == null ? EMPTY : relative.getParent())) {
                return parent.getFileAttributeView(relative.getFileName(), BasicFileAttributeView.class, NOFOLLOW).readAttributes();
            }
        }

        Handle open(Path relative) throws IOException {
            verifyRoot();
            if (!validRelative(relative)) {
                throw new IOException("Nieprawidłowa ścieżka: " + relative);
            }
            SecureDirectoryStream<Path> parent = directory(relative.getParent() == null ? EMPTY : relative.getParent());
            try {
                BasicFileAttributeView view = parent.getFileAttributeView(relative.getFileName(), BasicFileAttributeView.class, NOFOLLOW);
                BasicFileAttributes before = view.readAttributes();
                if (!before.isRegularFile() || before.isSymbolicLink()) {
                    throw new NoSuchFileException(relative.toString(), null, "Nie jest zwykłym plikiem");
                }
                SeekableByteChannel channel = parent.newByteChannel(relative.getFileName(),
                        Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                return new Handle(parent, channel, view, before);
            } catch (IOException | RuntimeException e) {
                parent.close();
                throw e;
            }
        }

        void scan(Consumer<Entry> files, Consumer<Entry> directories, Consumer<Exception> errors) throws IOException {
            verifyRoot();
            ArrayDeque<Path> queue = new ArrayDeque<>();
            queue.add(EMPTY);
            while (!queue.isEmpty() && !Thread.currentThread().isInterrupted()) {
                Path relative = queue.removeFirst();
                try (SecureDirectoryStream<Path> stream = directory(relative)) {
                    directories.accept(new Entry(relative, stream.getFileAttributeView(BasicFileAttributeView.class).readAttributes()));
                    for (Path listed : stream) {
                        Path name = listed.getFileName();
                        Path child = relative.resolve(name);
                        try {
                            BasicFileAttributes a = stream.getFileAttributeView(name, BasicFileAttributeView.class, NOFOLLOW).readAttributes();
                            if (a.isRegularFile()) {
                                files.accept(new Entry(child, a));
                            } else if (a.isDirectory()) {
                                if (child.getNameCount() <= MAX_DEPTH) {
                                    queue.addLast(child);
                                } else {
                                    errors.accept(new IOException("Przekroczona głębokość katalogów: " + child));
                                }
                            }
                        } catch (NoSuchFileException ignored) {
                        } catch (IOException e) {
                            errors.accept(e);
                        }
                    }
                } catch (NoSuchFileException ignored) {
                } catch (IOException | DirectoryIteratorException e) {
                    errors.accept(e);
                }
            }
        }

        public void close() throws IOException {
            anchor.close();
        }

        static final class Handle implements AutoCloseable {
            final SecureDirectoryStream<Path> parent;
            final SeekableByteChannel channel;
            final BasicFileAttributeView view;
            final BasicFileAttributes before;

            Handle(SecureDirectoryStream<Path> parent, SeekableByteChannel channel,
                   BasicFileAttributeView view, BasicFileAttributes before) {
                this.parent = parent;
                this.channel = channel;
                this.view = view;
                this.before = before;
            }

            public void close() throws IOException {
                try {
                    channel.close();
                } finally {
                    parent.close();
                }
            }
        }
    }

    static final class BackupManager implements AutoCloseable {
        final Config config;
        final SourceTree source;
        final UserPrincipal owner;
        final Path snapshots;
        final Path staging;
        final ConcurrentMap<Path, Saved> saved = new ConcurrentHashMap<>();
        final ConcurrentMap<Path, Pending> pending = new ConcurrentHashMap<>();
        final ScheduledThreadPoolExecutor executor;
        final AtomicBoolean stopped = new AtomicBoolean();
        final AtomicBoolean atimeWarning = new AtomicBoolean();
        final DateTimeFormatter timestamp;

        static final class Pending {
            long revision;
            int failures;
            boolean running;
            Version observed;
            ScheduledFuture<?> future;
        }

        BackupManager(Config config, SourceTree source, UserPrincipal owner) throws IOException {
            this.config = config;
            this.source = source;
            this.owner = owner;
            snapshots = config.archive.resolve(".snapshots");
            staging = config.archive.resolve(".staging");
            privateDirectory(snapshots, owner);
            privateDirectory(staging, owner);
            try (DirectoryStream<Path> leftovers = Files.newDirectoryStream(staging, "*.part")) {
                for (Path file : leftovers) {
                    if (Files.isRegularFile(file, NOFOLLOW)) {
                        Files.deleteIfExists(file);
                    }
                }
            }
            timestamp = DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss_SSSSSSSSS").withZone(config.zone);
            executor = new ScheduledThreadPoolExecutor(config.workers, threadFactory("backup"));
            executor.setRemoveOnCancelPolicy(true);
            executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        }

        boolean backedUp(Path path, Version version) {
            Saved previous = saved.get(path);
            return previous != null && previous.version.equals(version) && Files.isRegularFile(previous.snapshot, NOFOLLOW);
        }

        void discover(Entry entry) {
            if (!backedUp(entry.relative, Version.of(entry.attributes)) && !pending.containsKey(entry.relative)) {
                request(entry.relative);
            }
        }

        void request(Path path) {
            if (!validRelative(path)) {
                return;
            }
            while (!stopped.get()) {
                Pending item = pending.computeIfAbsent(path, p -> new Pending());
                synchronized (item) {
                    if (pending.get(path) != item) {
                        continue;
                    }
                    item.revision++;
                    item.observed = null;
                    item.failures = 0;
                    if (!item.running) {
                        schedule(path, item, QUIET_MILLIS);
                    }
                    return;
                }
            }
        }

        void schedule(Path path, Pending item, long delay) {
            if (item.future != null) {
                item.future.cancel(false);
            }
            if (!stopped.get()) {
                long scheduledRevision = item.revision;
                try {
                    item.future = executor.schedule(() -> process(path, item, scheduledRevision), delay, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException e) {
                    if (!stopped.get()) {
                        log("ERROR", "Nie można zaplanować kopii: " + path, e);
                    }
                }
            }
        }

        void process(Path path, Pending item, long scheduledRevision) {
            Version previous;
            synchronized (item) {
                if (stopped.get() || pending.get(path) != item || item.running || item.revision != scheduledRevision) {
                    return;
                }
                item.running = true;
                item.future = null;
                previous = item.observed;
            }
            boolean done = false;
            Version observed = null;
            Exception failure = null;
            try {
                BasicFileAttributes attributes = source.attributes(path);
                Version current = Version.of(attributes);
                if (!attributes.isRegularFile() || backedUp(path, current)) {
                    done = true;
                } else if (!current.equals(previous)) {
                    observed = current;
                } else {
                    snapshot(path, current);
                    done = true;
                }
            } catch (NoSuchFileException | NotDirectoryException ignored) {
                done = true;
            } catch (UnstableFileException ignored) {
            } catch (IOException | RuntimeException e) {
                failure = e;
            } finally {
                synchronized (item) {
                    item.running = false;
                    if (stopped.get()) {
                        pending.remove(path, item);
                    } else if (item.revision != scheduledRevision) {
                        schedule(path, item, QUIET_MILLIS);
                    } else if (done) {
                        pending.remove(path, item);
                    } else {
                        item.observed = observed;
                        long delay = QUIET_MILLIS;
                        if (failure != null) {
                            item.failures++;
                            delay = Math.min(60_000L, 1_000L << Math.min(item.failures, 6));
                            if (item.failures == 1 || item.failures % 5 == 0) {
                                log("ERROR", "Kopia zostanie ponowiona: " + path + ", próba " + item.failures, failure);
                            }
                        }
                        schedule(path, item, delay);
                    }
                }
            }
        }

        void snapshot(Path relative, Version expected) throws IOException {
            Path temporary = null;
            Path published = null;
            try (SourceTree.Handle input = source.open(relative)) {
                if (!Version.of(input.before).equals(expected)) {
                    throw new UnstableFileException();
                }
                long free = Files.getFileStore(staging).getUsableSpace();
                if (free < config.reserveBytes || expected.size > free - config.reserveBytes) {
                    throw new IOException("Za mało wolnego miejsca na kopię i rezerwę dysku.");
                }
                temporary = Files.createTempFile(staging, "snapshot-", ".part", FILE_ATTRIBUTE);
                try {
                    try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                        ByteBuffer buffer = ByteBuffer.allocateDirect(128 * 1024);
                        long copied = 0;
                        int count;
                        while ((count = input.channel.read(buffer)) != -1) {
                            if (Thread.currentThread().isInterrupted()) {
                                throw new IOException("Kopiowanie przerwane podczas zatrzymywania usługi.");
                            }
                            copied = Math.addExact(copied, count);
                            if (copied > expected.size) {
                                throw new UnstableFileException();
                            }
                            buffer.flip();
                            while (buffer.hasRemaining()) {
                                output.write(buffer);
                            }
                            buffer.clear();
                        }
                        if (copied != expected.size || !expected.equals(Version.of(input.view.readAttributes()))) {
                            throw new UnstableFileException();
                        }
                        Files.setLastModifiedTime(temporary, expected.modified);
                        output.force(true);
                    }
                    Path directory = snapshots;
                    if (relative.getParent() != null) {
                        for (Path name : relative.getParent()) {
                            directory = directory.resolve(name);
                            privateDirectory(directory, owner);
                        }
                    }
                    if (!expected.equals(Version.of(input.view.readAttributes()))) {
                        throw new UnstableFileException();
                    }
                    String filename = timestamp.format(Instant.now()) + "_" + UUID.randomUUID() + "_" + shorten(relative.getFileName().toString(), 160);
                    Path target = directory.resolve(filename);
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                    temporary = null;
                    syncDirectory(directory);
                    saved.put(relative, new Saved(expected, target));
                    published = target;
                } finally {
                    try {
                        BasicFileAttributes after = input.view.readAttributes();
                        if (after.isRegularFile() && expected.equals(Version.of(after)) &&
                                !after.lastAccessTime().equals(input.before.lastAccessTime())) {
                            input.view.setTimes(null, input.before.lastAccessTime(), null);
                        }
                    } catch (IOException | RuntimeException e) {
                        if (atimeWarning.compareAndSet(false, true)) {
                            log("WARN", "Nie można przywrócić atime. Potrzebne są odpowiednie uprawnienia do plików źródłowych.", e);
                        }
                    }
                }
            } finally {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException e) {
                        log("WARN", "Nie można usunąć nieukończonej kopii: " + temporary, e);
                    }
                }
            }
            if (published != null) {
                log("INFO", "Utworzono kopię: " + relative + " -> " + published, null);
            }
        }

        public void close() {
            if (stopped.compareAndSet(false, true)) {
                shutdown(executor);
            }
        }
    }

    static final class UnstableFileException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    static final class Statistics {
        long count;
        long bytes;
        final PriorityQueue<Row> largest = new PriorityQueue<>(Comparator.comparingLong(Row::size).thenComparing(r -> r.path.toString()));
        final PriorityQueue<Row> recent = new PriorityQueue<>(Comparator.comparing(Row::activity).thenComparing(r -> r.path.toString()));

        void add(Path path, BasicFileAttributes a, String area) {
            count++;
            bytes = Math.addExact(bytes, a.size());
            FileTime activity = a.creationTime().compareTo(a.lastModifiedTime()) > 0 ? a.creationTime() : a.lastModifiedTime();
            Row row = new Row(path, a.size(), a.lastModifiedTime(), activity, area);
            retain(largest, row, 5);
            retain(recent, row, 20);
        }

        static void retain(PriorityQueue<Row> queue, Row row, int limit) {
            queue.add(row);
            if (queue.size() > limit) {
                queue.poll();
            }
        }

        static List<Row> descending(PriorityQueue<Row> queue) {
            List<Row> result = new ArrayList<>(queue);
            result.sort(queue.comparator().reversed());
            return result;
        }
    }

    static final class ReportGenerator {
        final Config config;
        final SourceTree source;
        final BackupManager backup;
        final DateTimeFormatter date;

        ReportGenerator(Config config, SourceTree source, BackupManager backup) {
            this.config = config;
            this.source = source;
            this.backup = backup;
            date = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss XXX").withZone(config.zone);
        }

        static String escape(String value) {
            return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;").replace("'", "&#39;");
        }

        static String mib(long bytes) {
            return String.format(Locale.forLanguageTag("pl-PL"), "%.2f", bytes / 1_048_576.0);
        }

        String table(List<Row> rows, boolean activity) {
            StringBuilder html = new StringBuilder("<div class=scroll><table><thead><tr><th>Nazwa</th><th>Ścieżka</th><th>Obszar</th><th>Rozmiar (MiB)</th><th>");
            html.append(activity ? "Dodanie / modyfikacja" : "Modyfikacja").append("</th></tr></thead><tbody>");
            for (Row row : rows) {
                html.append("<tr><td>").append(escape(row.path.getFileName().toString()))
                        .append("</td><td class=path>").append(escape(row.path.toString()))
                        .append("</td><td>").append(escape(row.area))
                        .append("</td><td class=number>").append(mib(row.size))
                        .append("</td><td>").append(date.format((activity ? row.activity : row.modified).toInstant())).append("</td></tr>");
            }
            if (rows.isEmpty()) {
                html.append("<tr><td colspan=5>Brak plików.</td></tr>");
            }
            return html.append("</tbody></table></div>").toString();
        }

        void generate() throws IOException {
            Statistics exchange = new Statistics();
            Statistics archive = new Statistics();
            List<String> problems = new ArrayList<>();
            long[] errors = {0};
            Consumer<Exception> recordError = e -> {
                errors[0]++;
                if (problems.size() < 10) {
                    problems.add(Objects.toString(e.getMessage(), e.getClass().getSimpleName()));
                }
            };
            Instant started = Instant.now();
            source.scan(e -> exchange.add(config.source.resolve(e.relative), e.attributes, "Wymiana"), e -> { }, recordError);
            Files.walkFileTree(backup.snapshots, new SimpleFileVisitor<>() {
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (Thread.currentThread().isInterrupted()) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (attributes.isRegularFile()) {
                        archive.add(file, attributes, "Archiwum");
                    }
                    return FileVisitResult.CONTINUE;
                }
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    recordError.accept(error);
                    return FileVisitResult.CONTINUE;
                }
                public FileVisitResult postVisitDirectory(Path directory, IOException error) {
                    if (error != null) {
                        recordError.accept(error);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Generowanie raportu przerwane.");
            }
            List<Row> combined = new ArrayList<>(exchange.largest);
            combined.addAll(archive.largest);
            combined.sort(Comparator.comparingLong(Row::size).reversed().thenComparing(r -> r.path.toString()));
            StringBuilder html = new StringBuilder("""
                    <!doctype html><html lang="pl"><head><meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width,initial-scale=1">
                    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
                    <title>Raport serwera szkolnego</title><style>
                    :root{color-scheme:light}body{margin:0;background:#f1f5f9;color:#172438;font:15px system-ui,sans-serif}
                    main{max-width:1280px;margin:auto;padding:36px 24px}header{border-bottom:3px solid #2563eb;padding-bottom:20px}
                    h1{margin:0 0 10px;font-size:30px}h2{margin-top:32px;font-size:21px}p{line-height:1.6}
                    .cards{display:flex;gap:16px;flex-wrap:wrap;margin:24px 0}.card{flex:1;min-width:200px;background:white;border-radius:12px;padding:20px}
                    .card strong{display:block;font-size:27px;color:#1d4ed8;margin:10px 0}.scroll{overflow-x:auto;background:white;border-radius:12px}
                    table{border-collapse:collapse;width:100%}th,td{text-align:left;padding:13px;border-bottom:1px solid #e2e8f0;vertical-align:top}
                    th{background:#e2e8f0;font-size:13px}tr:nth-child(even){background:#f8fafc}.path{overflow-wrap:anywhere;min-width:240px}
                    .number{text-align:right;white-space:nowrap}.warning{background:#fff2cc;padding:16px;border-left:4px solid #b77900}
                    footer{margin-top:32px;color:#475569}code{overflow-wrap:anywhere} @media print{main{padding:0}.scroll{overflow:visible}body{background:white}}
                    </style></head><body><main><header><h1>Raport serwera szkolnego</h1>
                    """);
            html.append("<p>Skanowanie: ").append(date.format(started)).append(" — ").append(date.format(Instant.now()))
                    .append("<br>Katalog wymiany: <code>").append(escape(config.source.toString()))
                    .append("</code><br>Archiwum: <code>").append(escape(config.archive.toString())).append("</code></p></header>");
            html.append("<div class=cards><div class=card>Folder wymiany<strong>").append(mib(exchange.bytes)).append(" MiB</strong>")
                    .append(exchange.count).append(" plików</div><div class=card>Kopie zapasowe<strong>")
                    .append(mib(archive.bytes)).append(" MiB</strong>").append(archive.count)
                    .append(" wersji</div><div class=card>Oczekujące kopie<strong>").append(backup.pending.size()).append("</strong>pliki w kolejce</div></div>");
            for (Path path : List.of(config.source, config.archive)) {
                try {
                    FileStore store = Files.getFileStore(path);
                    html.append("<p>System plików dla <code>").append(escape(path.toString())).append("</code>: pojemność ")
                            .append(mib(store.getTotalSpace())).append(" MiB; dostępne ").append(mib(store.getUsableSpace())).append(" MiB.</p>");
                } catch (IOException e) {
                    recordError.accept(e);
                }
            }
            if (errors[0] > 0) {
                html.append("<div class=warning>Raport niepełny. Błędy odczytu: ").append(errors[0]).append("<ul>");
                for (String problem : problems) {
                    html.append("<li>").append(escape(problem)).append("</li>");
                }
                html.append("</ul></div>");
            }
            html.append("<h2>TOP 5 największych plików — wymiana i archiwum</h2>")
                    .append(table(combined.subList(0, Math.min(5, combined.size())), false))
                    .append("<h2>TOP 5 największych plików uczniów — folder wymiany</h2>")
                    .append(table(Statistics.descending(exchange.largest), false))
                    .append("<h2>20 ostatnio dodanych lub zmodyfikowanych plików — folder wymiany</h2>")
                    .append(table(Statistics.descending(exchange.recent), true))
                    .append("<footer>Rozmiary logiczne plików: 1 MiB = 1 048 576 bajtów. Katalogi i dowiązania symboliczne są pomijane w sumach. ")
                    .append("Daty aktywności pochodzą z metadanych systemu plików; nie stanowią dziennika operacji użytkowników. ")
                    .append("Skanowanie odbywa się podczas pracy serwera i nie jest atomowym obrazem systemu plików.</footer></main></body></html>");
            Path temporary = Files.createTempFile(config.reports, ".report-", ".tmp", FILE_ATTRIBUTE);
            try {
                try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    ByteBuffer bytes = StandardCharsets.UTF_8.encode(html.toString());
                    while (bytes.hasRemaining()) {
                        output.write(bytes);
                    }
                    output.force(true);
                }
                Files.move(temporary, config.reports.resolve("raport_dzienny.html"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                syncDirectory(config.reports);
                log("INFO", "Zapisano raport. Wymiana: " + exchange.count + ", kopie: " + archive.count + ", błędy: " + errors[0], null);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    static final class FileWatcher implements AutoCloseable {
        record Registration(WatchKey key, Object identity) { }
        final Path root;
        final WatchService service;
        final ConcurrentMap<WatchKey, Path> keys = new ConcurrentHashMap<>();
        final Map<Path, Registration> directories = new HashMap<>();

        FileWatcher(Path root) throws IOException {
            this.root = root;
            service = root.getFileSystem().newWatchService();
        }

        synchronized void register(Entry entry) throws IOException {
            Path path = root.resolve(entry.relative);
            Registration old = directories.get(entry.relative);
            if (old != null && old.key.isValid() && Objects.equals(old.identity, entry.attributes.fileKey())) {
                return;
            }
            BasicFileAttributes current = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
            if (!current.isDirectory() || !Objects.equals(current.fileKey(), entry.attributes.fileKey())) {
                throw new IOException("Katalog zmienił się podczas rejestracji: " + path);
            }
            if (old != null) {
                old.key.cancel();
                keys.remove(old.key);
            }
            WatchKey key = path.register(service, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            keys.put(key, entry.relative);
            directories.put(entry.relative, new Registration(key, current.fileKey()));
        }

        synchronized void remove(WatchKey key) {
            Path path = keys.remove(key);
            if (path != null) {
                Registration registration = directories.get(path);
                if (registration != null && registration.key == key) {
                    directories.remove(path);
                }
            }
        }

        synchronized Path directoryFor(WatchKey key) {
            return keys.get(key);
        }

        public void close() throws IOException {
            service.close();
        }
    }

    final Config config;
    final SourceTree source;
    final BackupManager backup;
    final ReportGenerator reports;
    final FileWatcher watcher;
    final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(threadFactory("scan"));
    final ScheduledExecutorService reporting = Executors.newSingleThreadScheduledExecutor(threadFactory("report"));
    final AtomicBoolean rescan = new AtomicBoolean(true);
    final AtomicBoolean stopping = new AtomicBoolean();
    final CountDownLatch closed = new CountDownLatch(1);
    volatile long lastScan;

    SchoolBackupDaemon(Config config, SourceTree source, UserPrincipal owner) throws IOException {
        this.config = config;
        this.source = source;
        backup = new BackupManager(config, source, owner);
        try {
            watcher = new FileWatcher(config.source);
        } catch (IOException | RuntimeException e) {
            backup.close();
            throw e;
        }
        reports = new ReportGenerator(config, source, backup);
    }

    void reconcile() {
        if (stopping.get()) {
            return;
        }
        long elapsed = System.nanoTime() - lastScan;
        if (!rescan.getAndSet(false) && elapsed < TimeUnit.SECONDS.toNanos(config.rescanSeconds)) {
            return;
        }
        try {
            Set<Path> present = new HashSet<>();
            AtomicBoolean complete = new AtomicBoolean(true);
            source.scan(e -> {
                present.add(e.relative);
                backup.discover(e);
            }, e -> {
                try {
                    watcher.register(e);
                } catch (IOException e1) {
                    log("ERROR", "Nie można monitorować katalogu: " + e.relative + "; sprawdź uprawnienia i limity inotify.", e1);
                }
            }, e -> {
                complete.set(false);
                log("WARN", "Niepełne skanowanie folderu wymiany", e);
            });
            if (complete.get() && !Thread.currentThread().isInterrupted()) {
                backup.saved.keySet().removeIf(path -> !present.contains(path));
            }
        } catch (IOException | RuntimeException e) {
            log("ERROR", "Błąd uzgadniania plików", e);
        } finally {
            lastScan = System.nanoTime();
        }
    }

    void run() throws IOException, InterruptedException {
        watcher.register(new Entry(EMPTY, Files.readAttributes(config.source, BasicFileAttributes.class, NOFOLLOW)));
        maintenance.scheduleWithFixedDelay(this::reconcile, 0, 1, TimeUnit.SECONDS);
        reporting.scheduleWithFixedDelay(() -> {
            try {
                reports.generate();
            } catch (IOException | RuntimeException e) {
                log("ERROR", "Nie udało się wygenerować raportu", e);
            }
        }, 0, config.reportSeconds, TimeUnit.SECONDS);
        log("INFO", "Monitorowanie: " + config.source + ". Archiwum: " + config.archive, null);
        log("INFO", "Przywracanie atime nie usuwa zmian ctime ani zapisów audytu SMB/SFTP. Prywatne katalogi muszą pozostawać poza udziałami uczniów.", null);
        try {
            while (!stopping.get()) {
                WatchKey key = watcher.service.take();
                Path directory = watcher.directoryFor(key);
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        rescan.set(true);
                        log("WARN", "Przepełnienie kolejki inotify; zaplanowano pełne skanowanie.", null);
                    } else if (directory != null && event.context() instanceof Path name) {
                        Path relative = directory.resolve(name);
                        if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                            backup.saved.keySet().removeIf(p -> p.startsWith(relative));
                        } else {
                            backup.request(relative);
                        }
                        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                            rescan.set(true);
                        }
                    }
                }
                if (!key.reset()) {
                    watcher.remove(key);
                    rescan.set(true);
                }
            }
        } catch (ClosedWatchServiceException e) {
            if (!stopping.get()) {
                throw e;
            }
        }
    }

    static void shutdown(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log("WARN", "Nie wszystkie zadania zakończyły się w limicie zamykania.", null);
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void close() {
        if (stopping.compareAndSet(false, true)) {
            try {
                try {
                    watcher.close();
                } catch (IOException e) {
                    log("WARN", "Błąd zamykania WatchService", e);
                }
                shutdown(maintenance);
                shutdown(reporting);
                backup.close();
            } finally {
                closed.countDown();
            }
        } else {
            try {
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @SuppressWarnings("try")
    public static void main(String[] args) {
        int status = 0;
        try {
            Config config = Config.parse(args);
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux") ||
                    !FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                throw new IOException("Program wymaga Linuksa i systemu plików z uprawnieniami POSIX.");
            }
            for (Path path : List.of(config.source, config.archive, config.reports)) {
                rejectSymlinkComponents(path);
            }
            if (!Files.isDirectory(config.source, NOFOLLOW)) {
                throw new IOException("Katalog wymiany nie istnieje: " + config.source);
            }
            UserPrincipal owner = config.archive.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
            privateDirectory(config.archive, owner);
            privateDirectory(config.reports, owner);
            try (InstanceLock instance = new InstanceLock(config.archive);
                 SourceTree source = new SourceTree(config.source);
                 SchoolBackupDaemon daemon = new SchoolBackupDaemon(config, source, owner)) {
                Thread hook = new Thread(daemon::close, "shutdown");
                Runtime.getRuntime().addShutdownHook(hook);
                try {
                    daemon.run();
                } finally {
                    daemon.close();
                    try {
                        Runtime.getRuntime().removeShutdownHook(hook);
                    } catch (IllegalStateException ignored) {
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            status = 1;
            log("ERROR", "Zatrzymano usługę", e);
        }
        if (status != 0) {
            System.exit(status);
        }
    }
}
