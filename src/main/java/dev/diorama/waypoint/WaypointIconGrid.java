package dev.diorama.waypoint;

/** Bounded native icon pages, with room for the title and the picker's footer. */
public record WaypointIconGrid(int columns, int rows, int pageSize, int pages, int page, int start, int visible) {
    public static WaypointIconGrid fit(int width, int height, int count, int wantedPage) {
        int cell = 34;
        int limit = Math.max(1, (width - 24) / cell);
        int maxRows = Math.max(1, (height - 64) / cell);
        int columns = Math.min(limit, Math.max(6, (count + maxRows - 1) / maxRows));
        if (count > columns * maxRows) {
            maxRows = Math.max(1, (height - 88) / cell);
            columns = Math.min(limit, Math.max(6, (count + maxRows - 1) / maxRows));
        }
        int size = columns * maxRows;
        int pages = Math.max(1, (count + size - 1) / size);
        int page = Math.max(0, Math.min(pages - 1, wantedPage));
        int start = page * size;
        int visible = Math.min(size, count - start);
        int rows = Math.max(1, (visible + columns - 1) / columns);
        return new WaypointIconGrid(columns, rows, size, pages, page, start, visible);
    }
}
