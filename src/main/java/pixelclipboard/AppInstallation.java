package pixelclipboard;

import java.awt.Component;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.JOptionPane;

/** Opens the native Windows installer, or the project's release page when it is not beside the app. */
final class AppInstallation {
    static final URI RELEASES = URI.create("https://github.com/atalamcom/pixel-clipboard/releases/latest");

    private AppInstallation() {}

    static String version() {
        try (var stream = AppInstallation.class.getResourceAsStream("/version.txt")) {
            if (stream == null) throw new IOException("Missing app version");
            String version = new String(stream.readAllBytes(),StandardCharsets.UTF_8).trim();
            if (!version.matches("[0-9]+(?:\\.[0-9]+){1,3}")) throw new IOException("Invalid app version");
            return version;
        } catch (IOException exception) { throw new IllegalStateException("Cannot read app version",exception); }
    }

    static Path applicationDirectory() {
        String launcher = System.getProperty("jpackage.app-path");
        if (launcher != null && !launcher.isBlank()) return Path.of(launcher).toAbsolutePath().getParent();
        try {
            Path jar = Path.of(AppInstallation.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path directory = Files.isDirectory(jar) ? jar : jar.getParent();
            return directory.getFileName().toString().equals("app") ? directory.getParent() : directory;
        } catch (Exception exception) { return Path.of(System.getProperty("user.dir")).toAbsolutePath(); }
    }

    static Path findInstaller(Path appDirectory, String version) {
        if (!version.matches("[0-9]+(?:\\.[0-9]+){1,3}")) return null;
        Path parent = appDirectory.toAbsolutePath().getParent();
        if (parent == null) return null;
        Path installer = parent.resolve("PixelClipboard-Setup-" + version + ".exe");
        return Files.isRegularFile(installer) ? installer : null;
    }

    static void openSetup(Component owner) {
        try {
            Desktop desktop = Desktop.getDesktop();
            Path installer = findInstaller(applicationDirectory(),version());
            if (installer != null) desktop.open(installer.toFile());
            else desktop.browse(RELEASES);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            PixelClipboard.log(exception);
            JOptionPane.showMessageDialog(owner,"Open the Windows installer from the project's Releases page:\n" + RELEASES,
                    "Windows setup",JOptionPane.INFORMATION_MESSAGE);
        }
    }
}
