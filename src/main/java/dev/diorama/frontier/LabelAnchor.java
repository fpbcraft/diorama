package dev.diorama.frontier;

import java.util.Arrays;

/** Finds room inside a polygon for an upright, centred label. Coordinates share the drawing plane. */
public record LabelAnchor(double x, double z, double clearance) {
    private static final double[] SAMPLES = {0.5, 0.25, 0.75};

    public static LabelAnchor inside(double[] polygon) {
        double[] heights = new double[polygon.length / 2];
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < heights.length; i++) {
            heights[i] = polygon[2 * i + 1];
            minX = Math.min(minX, polygon[2 * i]);
            maxX = Math.max(maxX, polygon[2 * i]);
        }
        Arrays.sort(heights);
        double low = heights[0], high = heights[heights.length - 1];
        double[] intersections = new double[heights.length];
        double centerX = (minX + maxX) / 2, centerZ = (low + high) / 2;
        LabelAnchor best = scan(polygon, intersections, centerZ, centerX, centerZ, null);
        // Search across the whole visible shape, not the average of its vertices.
        for (int i = 0; i < 17; i++) {
            double z = low + ((i + 0.5) / 17) * (high - low);
            best = scan(polygon, intersections, z, centerX, centerZ, best);
        }
        // A very thin arm can fall between the regular samples.
        for (int i = 1; best == null && i < heights.length; i++) {
            if (heights[i] > heights[i - 1]) {
                best = scan(polygon, intersections, (heights[i] + heights[i - 1]) / 2, centerX, centerZ, null);
            }
        }
        return best == null ? new LabelAnchor(polygon[0], polygon[1], 0) : best;
    }

    private static LabelAnchor scan(double[] polygon, double[] intersections, double z, double centerX, double centerZ, LabelAnchor best) {
        int count = 0;
        for (int i = 0, j = polygon.length - 2; i < polygon.length; j = i, i += 2) {
            double zi = polygon[i + 1], zj = polygon[j + 1];
            if ((zi > z) != (zj > z)) {
                intersections[count++] = polygon[i] + (z - zi) / (zj - zi) * (polygon[j] - polygon[i]);
            }
        }
        Arrays.sort(intersections, 0, count);
        for (int i = 0; i + 1 < count; i += 2) {
            double width = intersections[i + 1] - intersections[i];
            if (width <= 0) continue;
            for (double fraction : SAMPLES) {
                double x = intersections[i] + fraction * width;
                double distance = distance(polygon, x, z);
                if (best == null || distance > best.clearance
                    || (distance == best.clearance && Math.hypot(x - centerX, z - centerZ) < Math.hypot(best.x - centerX, best.z - centerZ))) {
                    best = new LabelAnchor(x, z, distance);
                }
            }
        }
        return best;
    }

    private static double distance(double[] polygon, double x, double z) {
        double distance = Double.POSITIVE_INFINITY;
        for (int i = 0, j = polygon.length - 2; i < polygon.length; j = i, i += 2) {
            double dx = polygon[i] - polygon[j], dz = polygon[i + 1] - polygon[j + 1];
            double rx = x - polygon[j], rz = z - polygon[j + 1];
            double length = dx * dx + dz * dz;
            double t = length == 0 ? 0 : Math.max(0, Math.min(1, (rx * dx + rz * dz) / length));
            double ex = rx - t * dx, ez = rz - t * dz;
            distance = Math.min(distance, ex * ex + ez * ez);
        }
        return Math.sqrt(distance);
    }
}
