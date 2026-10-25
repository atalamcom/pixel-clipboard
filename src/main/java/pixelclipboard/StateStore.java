package pixelclipboard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

final class StateStore {
    record Loaded(AppState state, String warning) {}
    private final Path directory;
    private final Path file;

    StateStore(Path directory) {
        this.directory = directory;
        this.file = directory.resolve("state.json");
    }

    static Path defaultDirectory() {
        String appData = System.getenv("APPDATA");
        return appData == null || appData.isBlank()
                ? Path.of(System.getProperty("user.home"), ".pixelclipboard")
                : Path.of(appData, "PixelClipboard");
    }

    Loaded load() throws IOException {
        if (!Files.exists(file)) return new Loaded(AppState.defaults(), null);
        try {
            return new Loaded(read(file), null);
        } catch (IllegalArgumentException exception) {
            // Copy before allowing the UI to save anything over a malformed file.
            Path preserved = directory.resolve("state.broken-" + Instant.now().toEpochMilli() + ".json");
            Files.copy(file, preserved);
            Path backup = directory.resolve("state.json.bak");
            if (Files.exists(backup)) {
                try {
                    return new Loaded(read(backup), "Recovered your tasks from the last backup.\nThe damaged file was preserved at:\n" + preserved);
                } catch (IllegalArgumentException ignored) { /* Both copies damaged: start safely. */ }
            }
            return new Loaded(AppState.defaults(), "The saved file could not be read. Its original contents were preserved at:\n" + preserved);
        }
    }

    private AppState read(Path path) throws IOException {
        return AppState.fromJson(Files.readString(path, StandardCharsets.UTF_8));
    }

    synchronized void save(String snapshot) throws IOException {
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "state-", ".tmp");
        try {
            Files.writeString(temporary, snapshot, StandardCharsets.UTF_8);
            if (Files.exists(file)) {
                // Only replace the recovery backup with a known valid save.
                try {
                    read(file);
                    Files.copy(file, directory.resolve("state.json.bak"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IllegalArgumentException ignored) { }
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
