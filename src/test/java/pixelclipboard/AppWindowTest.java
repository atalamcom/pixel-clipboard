package pixelclipboard;

import java.awt.Component;
import java.awt.Frame;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Opens the actual entry point using an isolated APPDATA supplied by the test runner. */
public final class AppWindowTest {
    public static void main(String[] arguments) {
        int exit = 1;
        try {
            Path directory = StateStore.defaultDirectory();
            if (!directory.toString().contains("test-output")) throw new IllegalStateException("Window test requires isolated APPDATA");
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("state.json"),"{\"version\":1,\"tasks\":[{\"text\":\"Existing PowerShell task\",\"done\":false}],\"topmost\":false,\"width\":420,\"height\":600}");
            PixelClipboard.main(new String[0]);
            CountDownLatch closed = new CountDownLatch(1);
            JFrame[] window = new JFrame[1];
            SwingUtilities.invokeAndWait(() -> {
                for (Frame frame : JFrame.getFrames()) if (frame instanceof JFrame candidate && candidate.getTitle().equals("Pixel Clipboard")) window[0] = candidate;
                JFrame frame = window[0];
                if (frame == null || !frame.isShowing()) throw new AssertionError("App window did not open");
                if (frame.getIconImages().isEmpty()) throw new AssertionError("App icon is missing");
                frame.addWindowListener(new WindowAdapter() {
                    @Override public void windowClosed(WindowEvent event) { closed.countDown(); }
                });
                frame.setSize(800,700);
                frame.validate();
                ClipboardPanel panel = (ClipboardPanel) frame.getContentPane();
                panel.doLayout();
                boolean edited = false;
                for (Component component : panel.getComponents()) if (component instanceof JPanel row && row.isVisible()) {
                    JTextField field = null;
                    JCheckBox checkbox = null;
                    for (Component child : row.getComponents()) {
                        if (child instanceof JTextField found) field = found;
                        if (child instanceof JCheckBox found) checkbox = found;
                    }
                    if (field != null && checkbox != null) {
                        if (!field.getText().equals("Existing PowerShell task")) throw new AssertionError("Legacy task was not loaded");
                        field.setText("Java app saved \uD83D\uDCCB العربية 日本語");
                        checkbox.doClick();
                        field.postActionEvent();
                        edited = true;
                        break;
                    }
                }
                if (!edited) throw new AssertionError("Task editor was not available");
                panel.heading.setText("My work");
                panel.heading.postActionEvent();
                boolean[] helpSeen = {false};
                Timer dismissHelp = new Timer(300,event -> {
                    for (Window dialog : Window.getWindows()) if (dialog instanceof JDialog shown && shown.isShowing()) {
                        helpSeen[0] = shown.getTitle().equals("Settings") && hasSetupButton(shown.getContentPane());
                        JCheckBox setting = findPin(shown.getContentPane());
                        if (setting != null) setting.doClick();
                        shown.dispose();
                    }
                });
                dismissHelp.setRepeats(false);
                dismissHelp.start();
                panel.settings.doClick();
                if (!helpSeen[0]) throw new AssertionError("Settings did not open");
                if (!frame.isAlwaysOnTop()) throw new AssertionError("Settings checkbox did not update the real window");
                panel.changePage(1);
                panel.changePage(-1);
                panel.minimize.doClick();
            });
            SwingUtilities.invokeAndWait(() -> {
                JFrame frame = window[0];
                if ((frame.getExtendedState() & Frame.ICONIFIED) == 0) throw new AssertionError("Minimize button failed");
                frame.setState(Frame.NORMAL);
            });
            SwingUtilities.invokeAndWait(() -> ((ClipboardPanel) window[0].getContentPane()).close.doClick());
            if (!closed.await(10,TimeUnit.SECONDS)) throw new AssertionError("Save and exit did not finish");
            AppState saved = new StateStore(directory).load().state();
            if (saved.tasks.size() != 3 || !saved.tasks.getFirst().done || !saved.tasks.getFirst().text.startsWith("Java app saved")) throw new AssertionError("Final changes were not saved");
            if (saved.page != 0 || saved.pageCount != 2 || saved.tasks.getLast().page != 1) throw new AssertionError("New pages were not saved independently of task count");
            if (!saved.title.equals("My work")) throw new AssertionError("The edited title was not saved");
            if (!saved.topmost || saved.bounds.width != 800 || saved.bounds.height != 700) throw new AssertionError("Window preferences were not saved");
            System.out.println("PASS: real window startup, legacy import, Unicode editing, checkbox, Enter insertion, Settings and pin, minimize, resize, save and exit");
            exit = 0;
        } catch (Throwable exception) { exception.printStackTrace(); }
        finally { System.exit(exit); }
    }

    private static JCheckBox findPin(java.awt.Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JCheckBox checkbox && checkbox.getText().equals("KEEP ON TOP")) return checkbox;
            if (child instanceof java.awt.Container container) {
                JCheckBox found = findPin(container);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean hasSetupButton(java.awt.Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof javax.swing.JButton button && button.getText().equals("WINDOWS SETUP")) return true;
            if (child instanceof java.awt.Container container && hasSetupButton(container)) return true;
        }
        return false;
    }
}
