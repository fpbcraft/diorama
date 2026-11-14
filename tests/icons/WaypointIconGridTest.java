package dev.diorama.waypoint;

public final class WaypointIconGridTest {
    public static void main(String[] args) {
        for (int width : new int[] {320, 427, 640, 1280}) for (int height : new int[] {240, 360, 480})
            for (int count : new int[] {1, 11, 35, 163, 512}) {
                int reached = 0;
                var first = WaypointIconGrid.fit(width, height, count, 0);
                for (int page = 0; page < first.pages(); page++) {
                    var grid = WaypointIconGrid.fit(width, height, count, page);
                    if (grid.columns() * 34 > width - 24 || grid.rows() * 34 + 28 + (grid.pages() > 1 ? 24 : 0) > height - 28)
                        throw new AssertionError("Icon page clips the title, grid or controls");
                    if (grid.start() != reached || grid.visible() <= 0 || grid.visible() > grid.columns() * grid.rows())
                        throw new AssertionError("An icon is unreachable or duplicated");
                    reached += grid.visible();
                }
                if (reached != count) throw new AssertionError("Missing icons");
                if (WaypointIconGrid.fit(width, height, count, 999).page() != first.pages() - 1)
                    throw new AssertionError("Resize did not clamp the selected page");
            }
        System.out.println("Icon grid: all icons reachable with title/footer inside small and large GUI sizes");
    }
}
