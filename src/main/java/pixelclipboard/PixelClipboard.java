package pixelclipboard;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;

/** Java desktop entry point. The Windows launcher and Java runtime are packaged by jpackage. */
public final class PixelClipboard {
    private static final int MIN_WIDTH = 360;
    private static final int MIN_HEIGHT = 490;
    private final AppState state;
    private final StateStore store;
    private final JFrame frame = new JFrame("Pixel Clipboard");
    private final ClipboardPanel panel;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable,"PixelClipboard-save"));
    private final Timer saveTimer;
    private volatile String latestSnapshot;
    private volatile boolean cleanExit;
    private boolean closing;
    private boolean saveErrorShown;

    public static void main(String[] arguments) {
        Path directory = StateStore.defaultDirectory();
        FileChannel channel = null;
        FileLock lock = null;
        try {
            Files.createDirectories(directory);
            channel = FileChannel.open(directory.resolve("app.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                JOptionPane.showMessageDialog(null,"Pixel Clipboard is already running.\nOpen it from the taskbar.","Pixel Clipboard",JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            StateStore store = new StateStore(directory);
            StateStore.Loaded loaded = store.load();
            FileChannel ownedChannel = channel;
            FileLock ownedLock = lock;
            SwingUtilities.invokeAndWait(() -> {
                PixelClipboard app = new PixelClipboard(loaded.state(),store);
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    app.writer.shutdown();
                    try {
                        app.writer.awaitTermination(5,TimeUnit.SECONDS);
                        if (!app.cleanExit && app.latestSnapshot != null) app.store.save(app.latestSnapshot);
                    } catch (Exception exception) { log(exception); }
                    finally {
                        try { ownedLock.close(); ownedChannel.close(); } catch (IOException exception) { log(exception); }
                    }
                },"PixelClipboard-shutdown"));
                Thread.setDefaultUncaughtExceptionHandler((thread,exception) -> {
                    log(exception);
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(app.frame,
                            "An unexpected error occurred. Your last saved tasks are preserved.\nDetails: " + directory.resolve("error.log"),
                            "Pixel Clipboard",JOptionPane.ERROR_MESSAGE));
                });
                app.frame.setVisible(true);
                if (loaded.warning() != null) JOptionPane.showMessageDialog(app.frame,loaded.warning(),"Recovered saved tasks",JOptionPane.WARNING_MESSAGE);
            });
        } catch (Exception exception) {
            log(exception);
            try { if (lock != null) lock.close(); if (channel != null) channel.close(); } catch (IOException ignored) {}
            JOptionPane.showMessageDialog(null,"Pixel Clipboard could not start.\n" + exception.getMessage()
                    + "\nDetails: " + directory.resolve("error.log"),"Pixel Clipboard",JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    private PixelClipboard(AppState state, StateStore store) {
        this.state = state;
        this.store = store;
        saveTimer = new Timer(350,event -> persist());
        saveTimer.setRepeats(false);
        frame.setUndecorated(true);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(MIN_WIDTH,MIN_HEIGHT));
        frame.setIconImage(ClipboardPanel.image("clipboard-icon.png"));
        frame.setAlwaysOnTop(state.topmost);
        panel = new ClipboardPanel(state,this::changed,() -> {
            state.topmost = !state.topmost;
            frame.setAlwaysOnTop(state.topmost);
            changed();
        },() -> frame.setState(Frame.ICONIFIED),this::close);
        frame.setContentPane(panel);
        frame.setBounds(visibleBounds(state));
        state.bounds = frame.getBounds();
        state.hasPosition = true;
        latestSnapshot = state.toJson();
        installWindowGestures();
        frame.addComponentListener(new ComponentAdapter() {
            @Override public void componentMoved(ComponentEvent event) { changed(); }
            @Override public void componentResized(ComponentEvent event) { changed(); }
        });
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { close(); }
        });
    }

    private static Rectangle visibleBounds(AppState state) {
        GraphicsConfiguration chosen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        if (state.hasPosition) {
            long largest = 0;
            for (var screen : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                GraphicsConfiguration configuration = screen.getDefaultConfiguration();
                Rectangle intersection = configuration.getBounds().intersection(state.bounds);
                long area = intersection.isEmpty() ? 0 : (long) intersection.width * intersection.height;
                if (area > largest) { largest = area; chosen = configuration; }
            }
        }
        Rectangle area = workingArea(chosen);
        int width = Math.min(Math.max(MIN_WIDTH,state.bounds.width),area.width);
        int height = Math.min(Math.max(MIN_HEIGHT,state.bounds.height),area.height);
        int x = state.hasPosition ? state.bounds.x : area.x + area.width - width - 30;
        int y = state.hasPosition ? state.bounds.y : area.y + 42;
        return new Rectangle(Math.max(area.x,Math.min(x,area.x + area.width - width)),
                Math.max(area.y,Math.min(y,area.y + area.height - height)),width,height);
    }

    private static Rectangle workingArea(GraphicsConfiguration configuration) {
        Rectangle area = new Rectangle(configuration.getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        area.x += insets.left; area.y += insets.top;
        area.width -= insets.left + insets.right; area.height -= insets.top + insets.bottom;
        return area;
    }

    private void snapshot() {
        if (frame.getExtendedState() == Frame.NORMAL) {
            state.bounds = frame.getBounds();
            state.hasPosition = true;
        }
        latestSnapshot = state.toJson();
    }

    private void changed() {
        if (closing) return;
        snapshot();
        saveTimer.restart();
    }

    private void persist() {
        snapshot();
        String json = latestSnapshot;
        writer.execute(() -> {
            try {
                store.save(json);
                SwingUtilities.invokeLater(() -> saveErrorShown = false);
            } catch (IOException exception) {
                log(exception);
                SwingUtilities.invokeLater(() -> {
                    if (!saveErrorShown && !closing) {
                        saveErrorShown = true;
                        JOptionPane.showMessageDialog(frame,"Could not save your latest changes.\n" + exception.getMessage()
                                + "\nKeep the app open and check that the save folder is writable.","Save failed",JOptionPane.ERROR_MESSAGE);
                    }
                });
            }
        });
    }

    private void close() {
        if (closing) return;
        closing = true;
        saveTimer.stop();
        snapshot();
        String json = latestSnapshot;
        frame.setEnabled(false);
        writer.execute(() -> {
            try {
                store.save(json);
                cleanExit = true;
                writer.shutdown();
                SwingUtilities.invokeLater(frame::dispose);
            } catch (IOException exception) {
                log(exception);
                SwingUtilities.invokeLater(() -> {
                    closing = false;
                    frame.setEnabled(true);
                    JOptionPane.showMessageDialog(frame,"The app stayed open because your changes could not be saved.\n"
                            + exception.getMessage(),"Save failed",JOptionPane.ERROR_MESSAGE);
                });
            }
        });
    }

    private void installWindowGestures() {
        MouseAdapter gesture = new MouseAdapter() {
            private Point start;
            private Rectangle original;
            private int edge;
            private boolean moving;
            @Override public void mousePressed(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) return;
                start = event.getLocationOnScreen();
                original = frame.getBounds();
                Point local = SwingUtilities.convertPoint(event.getComponent(),event.getPoint(),panel);
                if (panel.isTaskArea(local)) { start = null; return; }
                edge = event.getComponent() == panel ? edges(local) : 0;
                moving = edge == 0;
            }
            @Override public void mouseDragged(MouseEvent event) {
                if (start == null || frame.getExtendedState() != Frame.NORMAL) return;
                Point point = event.getLocationOnScreen();
                int dx = point.x - start.x, dy = point.y - start.y;
                Rectangle bounds = new Rectangle(original);
                if (moving) { bounds.x += dx; bounds.y += dy; }
                else {
                    if ((edge & 1) != 0) { bounds.width = Math.max(MIN_WIDTH,original.width - dx); bounds.x = original.x + original.width - bounds.width; }
                    if ((edge & 2) != 0) bounds.width = Math.max(MIN_WIDTH,original.width + dx);
                    if ((edge & 4) != 0) { bounds.height = Math.max(MIN_HEIGHT,original.height - dy); bounds.y = original.y + original.height - bounds.height; }
                    if ((edge & 8) != 0) bounds.height = Math.max(MIN_HEIGHT,original.height + dy);
                }
                frame.setBounds(bounds);
            }
            @Override public void mouseReleased(MouseEvent event) { start = null; changed(); }
            @Override public void mouseMoved(MouseEvent event) {
                if (event.getComponent() == panel) panel.setCursor(Cursor.getPredefinedCursor(cursor(edges(event.getPoint()))));
            }
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getComponent() == panel && event.getY() < 35 && event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    if (frame.getExtendedState() == Frame.NORMAL) {
                        snapshot();
                        frame.setMaximizedBounds(workingArea(frame.getGraphicsConfiguration()));
                        frame.setExtendedState(Frame.MAXIMIZED_BOTH);
                    } else frame.setExtendedState(Frame.NORMAL);
                }
            }
            private int edges(Point point) {
                if (frame.getExtendedState() != Frame.NORMAL) return 0;
                int result = 0;
                if (point.x < 12) result |= 1;
                else if (point.x >= panel.getWidth() - 12) result |= 2;
                if (point.y < 12) result |= 4;
                else if (point.y >= panel.getHeight() - 12) result |= 8;
                return result;
            }
            private int cursor(int edges) {
                return switch (edges) {
                    case 1 -> Cursor.W_RESIZE_CURSOR;
                    case 2 -> Cursor.E_RESIZE_CURSOR;
                    case 4 -> Cursor.N_RESIZE_CURSOR;
                    case 8 -> Cursor.S_RESIZE_CURSOR;
                    case 5 -> Cursor.NW_RESIZE_CURSOR;
                    case 6 -> Cursor.NE_RESIZE_CURSOR;
                    case 9 -> Cursor.SW_RESIZE_CURSOR;
                    case 10 -> Cursor.SE_RESIZE_CURSOR;
                    default -> Cursor.DEFAULT_CURSOR;
                };
            }
        };
        panel.addMouseListener(gesture);
        panel.addMouseMotionListener(gesture);
    }

    static void log(Throwable exception) {
        try {
            Path directory = StateStore.defaultDirectory();
            Files.createDirectories(directory);
            Path log = directory.resolve("error.log");
            StringWriter text = new StringWriter();
            exception.printStackTrace(new PrintWriter(text));
            if (Files.exists(log) && Files.size(log) > 1_000_000) Files.delete(log);
            Files.writeString(log,Instant.now() + System.lineSeparator() + text + System.lineSeparator(),
                    StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        } catch (IOException ignored) { exception.printStackTrace(); }
    }
}
