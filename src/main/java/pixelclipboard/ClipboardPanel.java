package pixelclipboard;

import java.awt.Color;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.TexturePaint;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JDialog;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.text.DefaultEditorKit;

final class ClipboardPanel extends JPanel {
    static final Color INK = new Color(0x493925);
    static final Color MUTED = new Color(0x78684F);
    static final Font TEXT = PixelFonts.at(16);
    private static final Font UI = PixelFonts.at(16);
    private final AppState state;
    private final Runnable changed;
    private final Runnable togglePin;
    private final BufferedImage background = image("clipboard-bg.png");
    private final BufferedImage paper = background.getSubimage(85,72,64,30);
    private final BufferedImage top = background.getSubimage(35,0,64,35);
    private final BufferedImage bottom = background.getSubimage(35,455,64,35);
    private final BufferedImage left = background.getSubimage(0,35,35,64);
    private final BufferedImage right = background.getSubimage(325,35,35,64);
    private final Icon unchecked = new PixelCheckIcon(false);
    private final Icon checked = new PixelCheckIcon(true);
    final JTextField heading = new JTextField();
    final JButton settings = woodenSettingsButton();
    final JButton minimize = button("_", false);
    final JButton close = button("X", false);
    private final JButton previous = button("<<", false);
    private final JButton next = button(">>", false);
    private final JLabel pageLabel = new JLabel("", SwingConstants.CENTER);
    private final List<TaskRow> rows = new ArrayList<>();
    private final Deque<Deleted> deleted = new ArrayDeque<>();
    private int capacity = 8;
    private int taskTop = 90;
    private double rowPitch = 36;
    private String titleBeforeEditing;

    private record Deleted(AppState.Task task, int index) {}

    ClipboardPanel(AppState state, Runnable changed, Runnable togglePin, Runnable minimizeWindow, Runnable closeWindow) {
        this.state = state;
        this.changed = changed;
        this.togglePin = togglePin;
        setLayout(null);
        setFocusable(true);
        setOpaque(true);
        setBackground(new Color(0xF2E7CE));
        javax.swing.UIManager.put("MenuItem.selectionBackground",new Color(0xE3D1AB));
        javax.swing.UIManager.put("MenuItem.selectionForeground",INK);
        heading.setFont(PixelFonts.at(24));
        heading.setText(state.title);
        heading.setHorizontalAlignment(SwingConstants.CENTER);
        heading.setForeground(INK);
        heading.setCaretColor(INK);
        heading.setOpaque(false);
        heading.setBorder(BorderFactory.createEmptyBorder());
        heading.setSelectionColor(new Color(0xDBC79C));
        heading.setSelectedTextColor(INK);
        heading.getAccessibleContext().setAccessibleName("Checklist title");
        heading.getDocument().addDocumentListener(new DocumentListener() {
            private void update() { state.title = heading.getText(); heading.setFont(PixelFonts.forText(state.title).deriveFont(24f)); changed.run(); }
            @Override public void insertUpdate(DocumentEvent event) { update(); }
            @Override public void removeUpdate(DocumentEvent event) { update(); }
            @Override public void changedUpdate(DocumentEvent event) { update(); }
        });
        heading.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent event) { titleBeforeEditing = heading.getText(); SwingUtilities.invokeLater(heading::selectAll); }
            @Override public void focusLost(FocusEvent event) { finishTitleEditing(); }
        });
        heading.addActionListener(event -> { finishTitleEditing(); requestFocusInWindow(); });
        bindKey(heading,"ESCAPE","cancel-title",() -> {
            if (titleBeforeEditing != null) heading.setText(titleBeforeEditing);
            finishTitleEditing(); requestFocusInWindow();
        });
        settings.setToolTipText("Settings (F1)");
        minimize.setToolTipText("Minimize");
        close.setToolTipText("Save and exit");
        previous.setToolTipText("Previous page (Ctrl+Page Up)");
        next.setToolTipText("Next page (Ctrl+Page Down)");
        pageLabel.setFont(UI);
        pageLabel.setForeground(INK);
        for (Component control : new Component[] {heading, settings, minimize, close, previous, next, pageLabel}) add(control);
        for (JButton frameButton : new JButton[] {minimize, close}) {
            frameButton.setForeground(new Color(0xF9E1B1));
            frameButton.setBackground(new Color(0x754326));
            frameButton.setOpaque(true);
            frameButton.setFont(PixelFonts.at(12));
        }
        settings.addActionListener(event -> showSettings());
        minimize.addActionListener(event -> minimizeWindow.run());
        close.addActionListener(event -> closeWindow.run());
        previous.addActionListener(event -> changePage(-1));
        next.addActionListener(event -> changePage(1));
        bindKey(this, "control N", "new", this::addTask);
        bindKey(this, "control shift Z", "undo-delete", this::undoDelete);
        bindKey(this, "control PAGE_UP", "previous", () -> changePage(-1));
        bindKey(this, "control PAGE_DOWN", "next", () -> changePage(1));
        bindKey(this, "control P", "pin", togglePin);
        bindKey(this, "F1", "settings", this::showSettings);
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"),"new");
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event) && isTaskArea(event.getPoint())) {
                    activateBlankSlot(Math.min(capacity - 1,(int) ((event.getY() - taskTop) / rowPitch)));
                }
            }
        });
    }

    private void finishTitleEditing() {
        String title = heading.getText().trim();
        if (title.isEmpty()) title = "CHECKLIST";
        if (!heading.getText().equals(title)) heading.setText(title);
        state.title = title;
    }

    boolean isTaskArea(java.awt.Point point) {
        return point.x >= 43 && point.x < getWidth() - 43 && point.y >= taskTop && point.y < getHeight() - 67;
    }

    void activateBlankSlot(int slot) {
        List<AppState.Task> visible = pageTasks(state.page);
        if (slot < 0) return;
        if (slot < visible.size()) { focusTask(visible.get(slot),0,false); return; }
        addTask();
    }

    static BufferedImage image(String name) {
        try (var stream = ClipboardPanel.class.getResourceAsStream("/assets/" + name)) {
            if (stream == null) throw new IOException("Missing image: " + name);
            BufferedImage image = ImageIO.read(stream);
            if (image == null) throw new IOException("Invalid image: " + name);
            return image;
        } catch (IOException exception) { throw new IllegalStateException("Cannot load app artwork", exception); }
    }

    private static JButton woodenSettingsButton() {
        Icon gear = new Icon() {
            private final String[] pixels = {"000111000","010111010","011111110","111000111","111000111","111000111","011111110","010111010","000111000"};
            @Override public int getIconWidth() { return 18; }
            @Override public int getIconHeight() { return 18; }
            @Override public void paintIcon(Component component,Graphics graphics,int x,int y) {
                graphics.setColor(new Color(0xF0D8AD));
                for (int row = 0; row < pixels.length; row++) for (int column = 0; column < pixels[row].length(); column++) {
                    if (pixels[row].charAt(column) == '1') graphics.fillRect(x + column * 2,y + row * 2,2,2);
                }
            }
        };
        JButton button = new JButton(gear) {
            @Override protected void paintComponent(Graphics graphics) {
                graphics.setColor(getModel().isPressed() ? new Color(0x613B24) : new Color(0x87532E));
                graphics.fillRect(0,0,getWidth(),getHeight());
                graphics.setColor(new Color(0xA87546));
                graphics.drawLine(2,4,getWidth() - 3,4);
                graphics.drawLine(2,getHeight() - 5,getWidth() - 3,getHeight() - 5);
                graphics.setColor(getModel().isRollover() || isFocusOwner() ? new Color(0xE0B983) : new Color(0xBF8F5C));
                graphics.drawRect(1,1,getWidth() - 3,getHeight() - 3);
                graphics.setColor(new Color(0x4D2D1D));
                graphics.drawRect(0,0,getWidth() - 1,getHeight() - 1);
                super.paintComponent(graphics);
            }
        };
        button.setContentAreaFilled(false); button.setOpaque(false);
        button.setBorder(BorderFactory.createEmptyBorder()); button.setFocusPainted(false);
        button.setRolloverEnabled(true); button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.getAccessibleContext().setAccessibleName("Settings");
        return button;
    }

    private static JButton button(String text, boolean filled) {
        JButton button = new JButton(text) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_OFF);
                    boolean active = getModel().isRollover() || getModel().isPressed() || isFocusOwner();
                    if (filled || isOpaque() || active || isSelected()) {
                        Color color = getBackground();
                        if (active) color = getModel().isPressed() ? new Color(0xD5C29C) : new Color(0xF1E2C2);
                        g.setColor(color);
                        g.fillRect(0,0,getWidth(),getHeight());
                    }
                    if (filled || isFocusOwner()) {
                        g.setColor(new Color(0x917C5C));
                        g.drawRect(0,0,getWidth() - 1,getHeight() - 1);
                    }
                } finally { g.dispose(); }
                super.paintComponent(graphics);
            }
        };
        button.setFont(UI);
        button.setForeground(INK);
        button.setBackground(new Color(0xE9DAB9));
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setBorder(BorderFactory.createEmptyBorder(2,3,2,3));
        button.setFocusPainted(false);
        button.setRolloverEnabled(true);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private static void bindKey(JComponent component, String key, String name, Runnable action) {
        component.getInputMap(component instanceof JTextField ? WHEN_FOCUSED : WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key), name);
        component.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { action.run(); }
        });
    }

    int pageCount() { return state.pageCount; }
    int capacity() { return capacity; }
    int visibleTaskCount() { return pageTasks(state.page).size(); }

    private List<AppState.Task> pageTasks(int page) {
        return state.tasks.stream().filter(task -> task.page == page).toList();
    }

    private void arrangePages() {
        int page = -1, count = 0;
        for (AppState.Task task : state.tasks) {
            int requested = task.page < 0 ? Math.max(0,page) : task.page;
            if (requested > page) { page = requested; count = 0; }
            if (count == capacity) { page++; count = 0; }
            task.page = page;
            count++;
        }
        state.pageCount = Math.max(state.pageCount,page + 1);
    }

    @Override public void doLayout() {
        int width = getWidth();
        int height = getHeight();
        heading.setBounds(44,40,width - 88,33);
        settings.setBounds(5,height - 32,26,26);
        minimize.setBounds(width - 63,5,21,20);
        close.setBounds(width - 39,5,21,20);
        previous.setBounds(44,height - 65,48,28);
        next.setBounds(width - 92,height - 65,48,28);
        pageLabel.setBounds(96,height - 65,width - 192,28);
        int newCapacity = Math.max(1, Math.min(100, (height - 67 - taskTop) / 36));
        if (rows.isEmpty() || newCapacity != capacity) rebuildRows(newCapacity);
        rowPitch = Math.max(36, (height - 67 - taskTop) / (double) capacity);
        for (int i = 0; i < rows.size(); i++) {
            TaskRow row = rows.get(i);
            row.setBounds(48, taskTop + (int) Math.round(i * rowPitch), width - 92, (int) Math.round(rowPitch) - 2);
            row.doLayout();
        }
    }

    private void rebuildRows(int newCapacity) {
        AppState.Task focusedTask = null;
        int caret = 0;
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        for (TaskRow row : rows) if (focus == row.edit) { focusedTask = row.task; caret = row.edit.getCaretPosition(); }
        for (TaskRow row : rows) remove(row);
        rows.clear();
        capacity = newCapacity;
        arrangePages();
        if (focusedTask != null) state.page = focusedTask.page;
        for (int i = 0; i < capacity; i++) { TaskRow row = new TaskRow(); rows.add(row); add(row); }
        renderRows();
        if (focusedTask != null) focusTask(focusedTask, caret, false);
    }

    void renderRows() {
        arrangePages();
        state.page = Math.max(0, Math.min(state.page, pageCount() - 1));
        List<AppState.Task> visible = pageTasks(state.page);
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).bind(i < visible.size() ? visible.get(i) : null);
        }
        pageLabel.setText((state.page + 1) + " / " + pageCount());
        previous.setEnabled(state.page > 0);
        next.setEnabled(true);
        repaint();
    }

    private void showSettings() {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),"Settings",java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setUndecorated(true);
        JPanel content = new JPanel(new BorderLayout(0,14)) {
            @Override public void paint(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
                    super.paint(g);
                } finally { g.dispose(); }
            }
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setPaint(new TexturePaint(paper,new Rectangle(0,0,paper.getWidth(),paper.getHeight())));
                    g.fillRect(0,0,getWidth(),getHeight());
                } finally { g.dispose(); }
            }
        };
        content.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0x754326),5),BorderFactory.createEmptyBorder(18,18,18,18)));
        JLabel title = new JLabel("SETTINGS",SwingConstants.CENTER);
        title.setFont(PixelFonts.at(24)); title.setForeground(INK);
        content.add(title,BorderLayout.NORTH);
        JPanel body = new JPanel(new BorderLayout(0,18)); body.setOpaque(false);
        JCheckBox pin = new JCheckBox("KEEP ON TOP",state.topmost);
        pin.setFont(UI); pin.setForeground(INK); pin.setOpaque(false);
        pin.setIcon(unchecked); pin.setSelectedIcon(checked);
        pin.setFocusPainted(false);
        pin.addActionListener(event -> togglePin.run());
        body.add(pin,BorderLayout.NORTH);
        JPanel shortcuts = new JPanel(new GridLayout(0,2,16,8)); shortcuts.setOpaque(false);
        String[][] keys = {
            {"ENTER","ADD TASK BELOW"}, {"CTRL+N","ADD TASK"}, {"CTRL+ENTER","COMPLETE / REOPEN"},
            {"BACKSPACE","DELETE EMPTY TASK"},
            {"UP / DOWN","EDIT NEIGHBOR"}, {"CTRL+UP / DOWN","MOVE TASK"}, {"CTRL+SHIFT+D","DELETE TASK"},
            {"CTRL+SHIFT+Z","UNDO DELETE"}, {"CTRL+PAGEUP/DOWN","SWITCH / NEW PAGE"},
            {"CTRL+P","KEEP ON TOP"}, {"ESC","FINISH EDITING"}, {"F1","SETTINGS"}
        };
        for (String[] pair : keys) for (String text : pair) {
            JLabel label = new JLabel(text); label.setFont(PixelFonts.at(12)); label.setForeground(INK); shortcuts.add(label);
        }
        body.add(shortcuts,BorderLayout.CENTER);
        JButton windowsSetup = button("WINDOWS SETUP",true);
        windowsSetup.setToolTipText("Install the app and choose Start menu or desktop shortcuts");
        windowsSetup.addActionListener(event -> { dialog.dispose(); AppInstallation.openSetup(this); });
        body.add(windowsSetup,BorderLayout.SOUTH);
        content.add(body,BorderLayout.CENTER);
        JPanel actions = new JPanel(new GridLayout(1,2,12,0)); actions.setOpaque(false);
        JButton undo = button("UNDO DELETE",true); undo.setEnabled(!deleted.isEmpty());
        undo.addActionListener(event -> { dialog.dispose(); undoDelete(); });
        JButton done = button("DONE",true); done.addActionListener(event -> dialog.dispose());
        actions.add(undo); actions.add(done); content.add(actions,BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(),KeyStroke.getKeyStroke("ESCAPE"),JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.pack(); dialog.setLocationRelativeTo(this); dialog.setVisible(true);
    }

    void addTask() {
        int index = state.tasks.size();
        for (int i = 0; i < state.tasks.size(); i++) if (state.tasks.get(i).page > state.page) { index = i; break; }
        createTask(index);
    }

    void insertTaskAfter(AppState.Task current) {
        int index = state.tasks.indexOf(current);
        if (index < 0) return;
        state.page = current.page;
        createTask(index + 1);
    }

    private void createTask(int index) {
        AppState.Task task = new AppState.Task("", false);
        task.page = state.page;
        state.tasks.add(index,task);
        arrangePages();
        state.page = task.page;
        renderRows();
        changed.run();
        focusTask(task, 0, true);
    }

    void toggleTask(AppState.Task task) {
        if (!state.tasks.contains(task)) return;
        task.done = !task.done;
        renderRows();
        changed.run();
    }

    void navigateTask(AppState.Task task, int delta, int caret) {
        int index = state.tasks.indexOf(task);
        int target = index + delta;
        if (index < 0 || target < 0 || target >= state.tasks.size()) return;
        state.page = state.tasks.get(target).page;
        renderRows();
        focusTask(state.tasks.get(target),caret,false);
        changed.run();
    }

    void removeTask(AppState.Task task) {
        int index = state.tasks.indexOf(task);
        if (index < 0) return;
        int rowIndex = pageTasks(state.page).indexOf(task);
        deleted.push(new Deleted(task, index));
        if (deleted.size() > 100) deleted.removeLast();
        state.tasks.remove(index);
        renderRows();
        changed.run();
        List<AppState.Task> remaining = pageTasks(state.page);
        if (!remaining.isEmpty()) {
            AppState.Task neighbor = remaining.get(Math.max(0,Math.min(rowIndex - 1,remaining.size() - 1)));
            focusTask(neighbor,neighbor.text.length(),false);
        } else requestFocusInWindow();
    }

    void undoDelete() {
        if (deleted.isEmpty()) return;
        Deleted item = deleted.pop();
        int index = Math.min(item.index(), state.tasks.size());
        state.tasks.add(index, item.task());
        arrangePages();
        state.page = item.task().page;
        renderRows();
        changed.run();
        focusTask(item.task(), item.task().text.length(), false);
    }

    void moveTask(AppState.Task task, int delta) {
        int index = state.tasks.indexOf(task);
        int destination = index + delta;
        if (index < 0 || destination < 0 || destination >= state.tasks.size()) return;
        AppState.Task neighbor = state.tasks.get(destination);
        int previousPage = task.page;
        task.page = neighbor.page;
        neighbor.page = previousPage;
        state.tasks.set(index,neighbor);
        state.tasks.set(destination,task);
        state.page = task.page;
        renderRows();
        changed.run();
        focusTask(task, task.text.length(), false);
    }

    void changePage(int delta) {
        int page = Math.max(0, state.page + delta);
        if (page == state.page) return;
        state.pageCount = Math.max(state.pageCount,page + 1);
        state.page = page;
        renderRows();
        List<AppState.Task> visible = pageTasks(page);
        if (visible.isEmpty()) addTask();
        else focusTask(visible.getFirst(),0,false);
        changed.run();
    }

    private void focusTask(AppState.Task task, int caret, boolean selectAll) {
        SwingUtilities.invokeLater(() -> {
            for (TaskRow row : rows) if (row.task == task) {
                row.edit.requestFocusInWindow();
                if (selectAll) row.edit.selectAll();
                else row.edit.setCaretPosition(Math.min(caret,row.edit.getText().length()));
                break;
            }
        });
    }

    @Override public void paint(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            super.paint(g);
        } finally { g.dispose(); }
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            int width = getWidth(), height = getHeight();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setPaint(new TexturePaint(paper,new Rectangle(0,0,paper.getWidth(),paper.getHeight())));
            g.fillRect(0,0,width,height);
            tile(g,top,35,0,width - 70,35);
            tile(g,bottom,35,height - 35,width - 70,35);
            tile(g,left,0,35,35,height - 70);
            tile(g,right,width - 35,35,35,height - 70);
            g.drawImage(background,0,0,35,35,0,0,35,35,null);
            g.drawImage(background,width - 35,0,width,35,325,0,360,35,null);
            g.drawImage(background,0,height - 35,35,height,0,455,35,490,null);
            g.drawImage(background,width - 35,height - 35,width,height,325,455,360,490,null);
            g.drawImage(background,(width - 60) / 2,4,(width + 60) / 2,18,150,4,210,18,null);
            g.setColor(new Color(0xB0A17D));
            g.drawLine(43,81,width - 43,81);
            g.setColor(new Color(0xD2C19E));
            for (int i = 1; i <= capacity; i++) {
                int y = taskTop + (int) Math.round(i * rowPitch) - 2;
                g.drawLine(43,y,width - 43,y);
            }
            g.setColor(new Color(0xD7B47D));
            for (int i = 0; i < 3; i++) g.drawLine(width - 12 - i * 4,height - 7,width - 7,height - 12 - i * 4);
        } finally { g.dispose(); }
    }

    private static void tile(Graphics2D graphics, BufferedImage image, int x, int y, int width, int height) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.clipRect(x,y,Math.max(0,width),Math.max(0,height));
            for (int yy = y; yy < y + height; yy += image.getHeight())
                for (int xx = x; xx < x + width; xx += image.getWidth()) g.drawImage(image,xx,yy,null);
        } finally { g.dispose(); }
    }

    private static final class PixelCheckIcon implements Icon {
        private final boolean selected;
        PixelCheckIcon(boolean selected) { this.selected = selected; }
        @Override public int getIconWidth() { return 24; }
        @Override public int getIconHeight() { return 24; }
        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            graphics.setColor(new Color(0x917F60));
            graphics.fillRect(x + 2,y + 2,20,2);
            graphics.fillRect(x + 2,y + 20,20,2);
            graphics.fillRect(x + 2,y + 2,2,20);
            graphics.fillRect(x + 20,y + 2,2,20);
            if (selected) {
                graphics.setColor(INK);
                for (int i = 0; i < 3; i++) graphics.fillRect(x + 5 + i * 2,y + 11 + i * 2,3,3);
                for (int i = 0; i < 6; i++) graphics.fillRect(x + 9 + i * 2,y + 15 - i * 2,3,3);
            }
        }
    }

    private final class TaskRow extends JPanel {
        final JCheckBox check = new JCheckBox();
        final JTextField edit = new JTextField();
        final JButton remove = button("x", false);
        AppState.Task task;
        boolean binding;

        TaskRow() {
            setLayout(null);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent event) {
                    if (SwingUtilities.isLeftMouseButton(event)) activateBlankSlot(rows.indexOf(TaskRow.this));
                }
            });
            check.setIcon(unchecked);
            check.setSelectedIcon(checked);
            check.setOpaque(false);
            check.setBorder(BorderFactory.createEmptyBorder());
            check.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            check.getAccessibleContext().setAccessibleName("Complete task");
            edit.setFont(TEXT);
            edit.setForeground(INK);
            edit.setCaretColor(INK);
            edit.setOpaque(false);
            edit.setBorder(BorderFactory.createEmptyBorder(2,1,2,1));
            edit.setSelectionColor(new Color(0xDBC79C));
            edit.setSelectedTextColor(INK);
            edit.getAccessibleContext().setAccessibleName("Task text");
            edit.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent event) {
                    if (SwingUtilities.isLeftMouseButton(event) && task != null && task.text.isBlank()) activateBlankSlot(rows.indexOf(TaskRow.this));
                }
            });
            ((AbstractDocument) edit.getDocument()).setDocumentFilter(new DocumentFilter() {
                @Override public void insertString(FilterBypass bypass, int offset, String text, AttributeSet attributes) throws BadLocationException {
                    super.insertString(bypass,offset,singleLine(text),attributes);
                }
                @Override public void replace(FilterBypass bypass, int offset, int length, String text, AttributeSet attributes) throws BadLocationException {
                    super.replace(bypass,offset,length,singleLine(text),attributes);
                }
                private String singleLine(String text) { return text == null ? null : text.replaceAll("[\r\n\t]+", " "); }
            });
            edit.getDocument().addDocumentListener(new DocumentListener() {
                private void update() { if (!binding && task != null) { task.text = edit.getText(); style(); changed.run(); } }
                @Override public void insertUpdate(DocumentEvent event) { update(); }
                @Override public void removeUpdate(DocumentEvent event) { update(); }
                @Override public void changedUpdate(DocumentEvent event) { update(); }
            });
            edit.addActionListener(event -> insertTaskAfter(task));
            remove.setForeground(MUTED);
            remove.setFont(UI);
            remove.getAccessibleContext().setAccessibleName("Delete task");
            check.addActionListener(event -> { if (task != null) toggleTask(task); });
            remove.addActionListener(event -> { if (task != null) removeTask(task); });
            add(check); add(edit); add(remove);
            bindKey(edit,"control UP","move-up",() -> moveTask(task,-1));
            bindKey(edit,"control DOWN","move-down",() -> moveTask(task,1));
            bindKey(edit,"UP","edit-previous",() -> navigateTask(task,-1,edit.getCaretPosition()));
            bindKey(edit,"DOWN","edit-next",() -> navigateTask(task,1,edit.getCaretPosition()));
            bindKey(edit,"control ENTER","toggle-complete",() -> toggleTask(task));
            bindKey(edit,"control shift D","delete-task",() -> removeTask(task));
            javax.swing.Action deleteCharacter = edit.getActionMap().get(DefaultEditorKit.deletePrevCharAction);
            bindKey(edit,"BACK_SPACE","delete-empty-or-character",() -> {
                if (task != null && edit.getText().isEmpty()) removeTask(task);
                else if (deleteCharacter != null) deleteCharacter.actionPerformed(new ActionEvent(edit,ActionEvent.ACTION_PERFORMED,DefaultEditorKit.deletePrevCharAction));
            });
            bindKey(edit,"ESCAPE","finish-editing",ClipboardPanel.this::requestFocusInWindow);
            JPopupMenu menu = new JPopupMenu();
            menu.setBackground(new Color(0xF2E7CE));
            menu.setBorder(BorderFactory.createLineBorder(new Color(0x917C5C)));
            menuItem(menu,"Cut",edit::cut);
            menuItem(menu,"Copy",edit::copy);
            menuItem(menu,"Paste",edit::paste);
            menuItem(menu,"Select all",edit::selectAll);
            menu.addSeparator();
            JMenuItem up = menuItem(menu,"Move task up",() -> moveTask(task,-1));
            JMenuItem down = menuItem(menu,"Move task down",() -> moveTask(task,1));
            menuItem(menu,"Delete task",() -> removeTask(task));
            menu.addPopupMenuListener(new PopupMenuListener() {
                @Override public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
                    int index = state.tasks.indexOf(task);
                    up.setEnabled(index > 0);
                    down.setEnabled(index >= 0 && index < state.tasks.size() - 1);
                }
                @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {}
                @Override public void popupMenuCanceled(PopupMenuEvent event) {}
            });
            edit.setComponentPopupMenu(menu);
            check.setComponentPopupMenu(menu);
        }

        private JMenuItem menuItem(JPopupMenu menu, String text, Runnable action) {
            JMenuItem item = new JMenuItem(text);
            item.setFont(PixelFonts.at(12));
            item.setForeground(INK);
            item.setBackground(new Color(0xF2E7CE));
            item.addActionListener(event -> action.run());
            menu.add(item);
            return item;
        }

        void bind(AppState.Task next) {
            binding = true;
            try {
                task = next;
                setVisible(true);
                check.setVisible(task != null);
                edit.setVisible(task != null);
                remove.setVisible(task != null);
                if (task == null) { edit.setText(""); return; }
                if (!edit.getText().equals(task.text)) edit.setText(task.text);
                check.setSelected(task.done);
                style();
            } finally { binding = false; }
        }

        private void style() {
            Font font = PixelFonts.forText(task.text);
            edit.setFont(task.done ? font.deriveFont(Map.of(TextAttribute.STRIKETHROUGH,TextAttribute.STRIKETHROUGH_ON)) : font);
            edit.setForeground(task.done ? MUTED : INK);
        }

        @Override public void doLayout() {
            int y = (getHeight() - 28) / 2;
            check.setBounds(0,y + 1,26,26);
            edit.setBounds(35,y,getWidth() - 65,28);
            remove.setBounds(getWidth() - 28,y,28,28);
        }
    }
}
