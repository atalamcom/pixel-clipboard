package pixelclipboard;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AppState {
    static final class Task {
        String text;
        boolean done;
        int page = -1;
        Task(String text, boolean done) { this.text = text; this.done = done; }
    }

    final List<Task> tasks = new ArrayList<>();
    boolean topmost;
    String title = "CHECKLIST";
    Rectangle bounds = new Rectangle(-1, -1, 420, 600);
    boolean hasPosition;
    int page;
    int pageCount = 1;

    static AppState defaults() {
        AppState state = new AppState();
        state.tasks.add(new Task("Click here to edit a task", false));
        state.tasks.add(new Task("Check me when done", false));
        state.tasks.add(new Task("Click a blank line to add a task", false));
        return state;
    }

    static AppState fromJson(String json) {
        Object parsed = Json.parse(json);
        if (!(parsed instanceof Map<?, ?> map)) throw new IllegalArgumentException("Saved state must be an object");
        if (!(map.get("tasks") instanceof List<?> tasks)) throw new IllegalArgumentException("Saved tasks must be a list");
        AppState state = new AppState();
        for (Object object : tasks) {
            if (!(object instanceof Map<?, ?> task) || !(task.get("text") instanceof String text)) {
                throw new IllegalArgumentException("Invalid saved task");
            }
            if (!(task.get("done") instanceof Boolean done)) throw new IllegalArgumentException("Invalid task completion flag");
            Task restored = new Task(text,done);
            restored.page = number(task.get("page"),-1);
            state.tasks.add(restored);
        }
        state.topmost = Boolean.TRUE.equals(map.get("topmost"));
        if (map.get("title") instanceof String title && !title.isBlank()) state.title = title;
        state.bounds = new Rectangle(number(map.get("x"), -1), number(map.get("y"), -1),
                Math.max(360, number(map.get("width"), 420)), Math.max(490, number(map.get("height"), 600)));
        state.hasPosition = map.get("x") instanceof Number && map.get("y") instanceof Number;
        state.page = Math.max(0, number(map.get("page"), 0));
        state.pageCount = Math.max(state.page + 1, Math.max(1,number(map.get("pageCount"),1)));
        return state;
    }

    private static int number(Object value, int fallback) {
        if (!(value instanceof Number number)) return fallback;
        long result;
        try { result = new java.math.BigDecimal(number.toString()).longValueExact(); }
        catch (ArithmeticException exception) { return fallback; }
        return result >= Integer.MIN_VALUE && result <= Integer.MAX_VALUE ? (int) result : fallback;
    }

    String toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("version", 4);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Task task : tasks) items.add(Map.of("text", task.text, "done", task.done, "page", task.page));
        map.put("tasks", items);
        map.put("topmost", topmost);
        map.put("title", title);
        map.put("x", hasPosition ? bounds.x : null);
        map.put("y", hasPosition ? bounds.y : null);
        map.put("width", bounds.width);
        map.put("height", bounds.height);
        map.put("page", page);
        map.put("pageCount", pageCount);
        return Json.write(map) + System.lineSeparator();
    }
}
