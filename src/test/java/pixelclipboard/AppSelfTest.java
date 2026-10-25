package pixelclipboard;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/** Regression checks for data migration, recovery, task actions, and responsive geometry. */
public final class AppSelfTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] arguments) throws Exception {
        Path output = Path.of(arguments[0]).toAbsolutePath();
        Files.createDirectories(output);
        String special = "Quotes \" \\ \n\t \u0001 \uD83D\uDCCB العربية 日本語";
        check(Json.parse(Json.write(special)).equals(special),"Unicode and escaped text must round-trip");
        check(Json.parse("\uFEFF[true,false,null,1,-2.5e2]") instanceof List<?>,"Read PowerShell UTF-8 BOM");
        for (String malformed : List.of("{", "[1,]", "01", "\"bad\ntext\"", "{\"x\":1,\"x\":2}", "1 trailing")) {
            boolean rejected = false;
            try { Json.parse(malformed); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected,"Malformed JSON must be rejected: " + malformed);
        }
        String legacy = "{\"version\":1,\"tasks\":[{\"text\":\"Legacy task\",\"done\":true}],\"topmost\":true,\"x\":-800,\"y\":42}";
        AppState migrated = AppState.fromJson(legacy);
        check(migrated.tasks.size() == 1 && migrated.tasks.getFirst().done,"Migrate PowerShell tasks");
        check(migrated.topmost && migrated.hasPosition && migrated.bounds.x == -800,"Keep pin and multiple-monitor position");
        migrated.tasks.add(new AppState.Task(special,false));
        check(AppState.fromJson(migrated.toJson()).tasks.get(1).text.equals(special),"Saved state preserves Unicode");
        AppState empty = new AppState();
        check(AppState.fromJson(empty.toJson()).tasks.isEmpty(),"An empty checklist stays empty");
        Path data = Files.createTempDirectory(output,"state-test-");
        StateStore store = new StateStore(data);
        store.save(migrated.toJson());
        check(store.load().state().tasks.size() == 2,"Save and reload tasks");
        store.save(empty.toJson());
        check(Files.exists(data.resolve("state.json.bak")),"Saving keeps a recovery backup");
        Files.writeString(data.resolve("state.json"),"broken save");
        StateStore.Loaded recovered = store.load();
        check(recovered.warning() != null && recovered.state().tasks.size() == 2,"Recover from the valid backup");
        try (var paths = Files.list(data)) {
            check(paths.anyMatch(path -> path.getFileName().toString().startsWith("state.broken-")),"Preserve damaged saves");
        }
        check(AppInstallation.version().matches("[0-9]+(?:\\.[0-9]+){1,3}"),"Installer version must be bundled");
        Path portable = Files.createDirectories(output.resolve("installer-location/PixelClipboard"));
        Path installer = portable.getParent().resolve("PixelClipboard-Setup-" + AppInstallation.version() + ".exe");
        Files.write(installer,new byte[0]);
        check(AppInstallation.findInstaller(portable,AppInstallation.version()).equals(installer),"Settings locates the matching installer beside the portable app");
        check(AppInstallation.findInstaller(portable,"../untrusted") == null,"Installer discovery must not accept path components");
        Files.delete(installer);
        check(AppInstallation.findInstaller(portable,AppInstallation.version()) == null,"Missing installer uses the release-page fallback");
        SwingUtilities.invokeAndWait(() -> uiChecks(output));
        System.out.println("PASS: " + checks + " regression checks");
    }

    private static void uiChecks(Path output) {
        try {
            AppState state = new AppState();
            for (int i = 1; i <= 40; i++) state.tasks.add(new AppState.Task("Task " + i,i % 4 == 0));
            ClipboardPanel panel = new ClipboardPanel(state,() -> {},() -> {},() -> {},() -> {});
            for (Dimension size : List.of(new Dimension(360,490),new Dimension(420,600),new Dimension(1500,740),new Dimension(500,850),new Dimension(360,490))) {
                panel.setSize(size);
                panel.doLayout();
                check(panel.capacity() >= 6,"Keep readable task spacing at supported sizes");
                check(panel.visibleTaskCount() <= panel.capacity(),"Respect page capacity");
                check(panel.settings.getX() < 35 && panel.settings.getY() >= size.height - 35,"Settings belongs on the lower-left wooden rim");
                check(panel.settings.getIcon() != null && panel.settings.getText().isEmpty(),"Settings must use a symbol");
                check(java.util.Arrays.stream(panel.getComponents()).noneMatch(control -> control instanceof javax.swing.JButton button && button.getText().contains("ADD")),"Remove the Add button");
                Rectangle paper = new Rectangle(35,35,size.width - 70,size.height - 70);
                for (Component component : panel.getComponents()) {
                    if (!(component instanceof javax.swing.JPanel row) || !row.isVisible()) continue;
                    check(paper.contains(row.getBounds()),"Keep task rows inside the paper");
                    for (Component child : row.getComponents()) {
                        if (child instanceof JCheckBox) check(child.getWidth() == child.getHeight(),"Checkbox stays square");
                        if (child instanceof JTextField field) {
                            check(child.getX() + child.getWidth() <= row.getWidth(),"Editor stays inside row");
                            check(!field.isOpaque(),"Task fields must blend into the parchment");
                            check(field.getFont().getFamily().equals("Minecraft"),"Use the bundled pixel font");
                        }
                    }
                }
                BufferedImage image = new BufferedImage(size.width,size.height,BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics();
                panel.printAll(graphics);
                graphics.dispose();
                ImageIO.write(image,"png",output.resolve("java-layout-" + size.width + "x" + size.height + ".png").toFile());
            }
            AppState.Task task = state.tasks.getFirst();
            panel.moveTask(task,1);
            check(state.tasks.get(1) == task,"Move task down");
            panel.removeTask(task);
            check(!state.tasks.contains(task),"Delete task");
            panel.undoDelete();
            check(state.tasks.get(1) == task,"Undo restores the original position");
            int count = state.tasks.size();
            panel.addTask();
            check(state.tasks.size() == count + 1 && state.tasks.stream().anyMatch(item -> item.text.isEmpty() && item.page == state.page),"New task opens on its page");
            panel.changePage(-1);
            check(state.page < panel.pageCount(),"Page navigation remains valid");
            int retainedPages = panel.pageCount();
            for (AppState.Task item : List.copyOf(state.tasks)) panel.removeTask(item);
            check(panel.pageCount() == retainedPages,"Deleting tasks must keep created pages");
            panel.undoDelete();
            check(state.tasks.size() == 1,"Undo works after emptying checklist");
            shortcutChecks();
            pageChecks();
            editingChecks();
            System.out.println("PASS: UI renders at normal, wide, and tall sizes");
        } catch (Exception exception) { throw new RuntimeException(exception); }
    }

    private static void shortcutChecks() {
        AppState state = new AppState();
        for (int i = 1; i <= 8; i++) state.tasks.add(new AppState.Task("Original " + i,false));
        ClipboardPanel panel = new ClipboardPanel(state,() -> {},() -> { state.topmost = !state.topmost; },() -> {},() -> {});
        panel.setSize(360,490);
        panel.doLayout();
        AppState.Task originalNext = state.tasks.get(1);
        JTextField first = editor(panel,"Original 1");
        first.setText("Task being edited");
        first.postActionEvent();
        check(state.tasks.size() == 9,"Enter must create a task");
        check(state.tasks.get(1).text.isEmpty() && state.tasks.get(2) == originalNext,"Enter inserts immediately after the current task");
        JTextField blank = editor(panel,"");
        blank.postActionEvent();
        check(state.tasks.size() == 10,"Enter must also create tasks when the current task is empty");
        blank.setText("Inserted task");
        shortcut(blank,"control ENTER",JComponent.WHEN_FOCUSED);
        check(state.tasks.get(1).done,"Ctrl+Enter completes the edited task");
        shortcut(blank,"control ENTER",JComponent.WHEN_FOCUSED);
        check(!state.tasks.get(1).done,"Ctrl+Enter also reopens a task");
        shortcut(blank,"control DOWN",JComponent.WHEN_FOCUSED);
        check(state.tasks.get(2).text.equals("Inserted task"),"Ctrl+Down reorders the edited task");
        JTextField moved = editor(panel,"Inserted task");
        shortcut(moved,"control shift D",JComponent.WHEN_FOCUSED);
        check(state.tasks.stream().noneMatch(task -> task.text.equals("Inserted task")),"Delete shortcut removes the correct task");
        shortcut(panel,"control shift Z",JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        check(state.tasks.get(2).text.equals("Inserted task"),"Undo shortcut restores the deleted task");
        shortcut(panel,"control P",JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        check(state.topmost,"Pin shortcut still updates the setting");
        check(panel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(KeyStroke.getKeyStroke("F1")) != null,"F1 must open Settings");
        JTextField last = editor(panel,state.tasks.get(panel.capacity() - 1).text);
        last.postActionEvent();
        check(state.page == 1,"Enter at the bottom of a page follows the newly inserted task");
        JTextField inserted = editor(panel,"");
        shortcut(inserted,"UP",JComponent.WHEN_FOCUSED);
        check(state.page == 0,"Up navigates across page boundaries");
    }

    private static void pageChecks() {
        AppState state = new AppState();
        ClipboardPanel panel = new ClipboardPanel(state,() -> {},() -> {},() -> {},() -> {});
        panel.setSize(360,490);
        panel.doLayout();
        check(state.page == 0 && panel.pageCount() == 1,"An empty checklist starts at 1/1");
        panel.changePage(1);
        check(state.page == 1 && panel.pageCount() == 2,"Next from 1/1 creates and opens 2/2");
        check(state.tasks.size() == 1 && state.tasks.getFirst().page == 1,"New page gets an editable blank task on that page");
        panel.changePage(-1);
        check(state.page == 0 && panel.pageCount() == 2,"Previous from 2/2 shows 1/2");
        editor(panel,"").setText("First page task");
        panel.changePage(1);
        check(editor(panel,"").getText().isEmpty(),"Pages keep their separate task contents");
        editor(panel,"").setText("Second page task");
        panel.changePage(1);
        check(state.page == 2 && panel.pageCount() == 3,"Next creates more pages even when the current page is not full");
        AppState.Task empty = state.tasks.stream().filter(task -> task.page == 2).findFirst().orElseThrow();
        panel.removeTask(empty);
        AppState restored = AppState.fromJson(state.toJson());
        ClipboardPanel reopened = new ClipboardPanel(restored,() -> {},() -> {},() -> {},() -> {});
        reopened.setSize(420,600);
        reopened.doLayout();
        check(restored.page == 2 && restored.pageCount == 3,"Empty pages and the current page survive reopening");
        reopened.changePage(-1);
        check(editor(reopened,"Second page task").getText().equals("Second page task"),"Saved tasks stay on their pages");
        reopened.changePage(-1);
        check(editor(reopened,"First page task").getText().equals("First page task"),"Earlier pages retain their contents");
        reopened.addTask();
        check(restored.tasks.get(1).page == 0,"Add inserts on the current page, before later pages");
        for (int i = 0; i < 15; i++) reopened.addTask();
        check(restored.tasks.stream().map(task -> task.text).filter(text -> !text.isEmpty()).toList().equals(List.of("First page task","Second page task")),"Overflow preserves every task and its order");
        check(restored.tasks.stream().allMatch(task -> task.page >= 0),"All new and migrated tasks have a page");
        for (int page = 0; page < restored.pageCount; page++) {
            final int checkedPage = page;
            check(restored.tasks.stream().filter(task -> task.page == checkedPage).count() <= reopened.capacity(),"Overflow never exceeds page capacity");
        }
    }

    private static JTextField editor(ClipboardPanel panel, String text) {
        for (Component component : panel.getComponents()) if (component instanceof javax.swing.JPanel row && row.isVisible()) {
            for (Component child : row.getComponents()) if (child instanceof JTextField field && field.isVisible() && field.getText().equals(text)) return field;
        }
        throw new AssertionError("No visible editor for " + text);
    }

    private static void shortcut(JComponent component, String key, int scope) {
        Object name = component.getInputMap(scope).get(KeyStroke.getKeyStroke(key));
        check(name != null && component.getActionMap().get(name) != null,"Shortcut must be bound: " + key);
        component.getActionMap().get(name).actionPerformed(new java.awt.event.ActionEvent(component,0,name.toString()));
    }

    private static void editingChecks() {
        AppState state = new AppState();
        state.tasks.add(new AppState.Task("First",false));
        state.tasks.add(new AppState.Task("Second",false));
        ClipboardPanel panel = new ClipboardPanel(state,() -> {},() -> {},() -> {},() -> {});
        panel.setSize(360,490);
        panel.doLayout();
        List<javax.swing.JPanel> slots = java.util.Arrays.stream(panel.getComponents()).filter(component -> component instanceof javax.swing.JPanel).map(component -> (javax.swing.JPanel) component).toList();
        javax.swing.JPanel fourth = slots.get(3);
        fourth.dispatchEvent(new java.awt.event.MouseEvent(fourth,java.awt.event.MouseEvent.MOUSE_CLICKED,System.currentTimeMillis(),0,50,10,1,false,java.awt.event.MouseEvent.BUTTON1));
        check(state.tasks.size() == 3 && state.tasks.get(2).text.isEmpty(),"Clicking blank row four creates row three, without gaps");
        fourth.dispatchEvent(new java.awt.event.MouseEvent(fourth,java.awt.event.MouseEvent.MOUSE_CLICKED,System.currentTimeMillis(),0,50,10,1,false,java.awt.event.MouseEvent.BUTTON1));
        check(state.tasks.size() == 4 && state.tasks.get(2).text.isEmpty() && state.tasks.get(3).text.isEmpty(),"Clicking an unused row creates a new entry even when the last entry is blank");
        panel.activateBlankSlot(2);
        check(state.tasks.size() == 4,"Existing empty entries remain editable without creating duplicates");
        JTextField blank = editor(panel,"");
        AppState.Task empty = state.tasks.get(2);
        shortcut(blank,"BACK_SPACE",JComponent.WHEN_FOCUSED);
        check(state.tasks.size() == 3 && !state.tasks.contains(empty),"Backspace deletes an empty entry");
        panel.undoDelete();
        check(state.tasks.get(2) == empty,"Undo restores a Backspace deletion");
        JTextField first = editor(panel,"First");
        first.setCaretPosition(first.getText().length());
        shortcut(first,"BACK_SPACE",JComponent.WHEN_FOCUSED);
        check(state.tasks.size() == 4 && state.tasks.getFirst().text.equals("Firs"),"Backspace edits nonempty task text normally");
        check(first.getToolTipText() == null,"Typing must not create an entry tooltip");
        for (javax.swing.JPanel slot : slots) for (Component child : slot.getComponents()) {
            if (child instanceof JComponent component) check(component.getToolTipText() == null,"Entry controls must have no hover tooltips");
        }
        panel.heading.setText("My tasks");
        panel.heading.postActionEvent();
        check(state.title.equals("My tasks"),"Click-editable heading updates the title");
        check(AppState.fromJson(state.toJson()).title.equals("My tasks"),"Custom title persists after reopening");
        panel.heading.setText(" ");
        panel.heading.postActionEvent();
        check(state.title.equals("Checklist"),"An empty heading falls back to Checklist");
    }
}
