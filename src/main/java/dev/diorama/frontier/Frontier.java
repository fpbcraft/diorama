package dev.diorama.frontier;

import java.util.UUID;

/**
 * A named, dimension-bound territory. Vertex coordinates are world X/Z, not projected
 * map pixels, so territories survive map rotation, pitch changes and map selection.
 */
public final class Frontier {
    public static final int MAX_VERTICES = 256;

    public UUID id = UUID.randomUUID();
    public String dimension = "";
    public String name = "";
    public int color = 0x55AA77;
    public boolean visible = true;
    /** Pairs of world X and Z coordinates. */
    public double[] vertices = new double[0];

    public Frontier() {
    }

    public Frontier copy() {
        Frontier copy = new Frontier();
        copy.id = this.id;
        copy.dimension = this.dimension;
        copy.name = this.name;
        copy.color = this.color;
        copy.visible = this.visible;
        copy.vertices = this.vertices.clone();
        return copy;
    }

    public boolean contains(double x, double z) {
        if (this.vertices == null || this.vertices.length < 6) {
            return false;
        }
        boolean inside = false;
        int count = this.vertices.length / 2;
        for (int i = 0, j = count - 1; i < count; j = i++) {
            double xi = this.vertices[2 * i];
            double zi = this.vertices[2 * i + 1];
            double xj = this.vertices[2 * j];
            double zj = this.vertices[2 * j + 1];
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    public double[] center() {
        double x = 0;
        double z = 0;
        int n = this.vertices.length / 2;
        for (int i = 0; i < this.vertices.length; i += 2) {
            x += this.vertices[i];
            z += this.vertices[i + 1];
        }
        return new double[] {x / n, z / n};
    }

    public boolean valid() {
        if (this.id == null || this.dimension == null || this.dimension.isBlank() || this.dimension.length() > 128
            || this.name == null || this.name.isBlank() || this.name.length() > 48
            || this.vertices == null || this.vertices.length < 6
            || (this.vertices.length & 1) != 0 || this.vertices.length / 2 > MAX_VERTICES) {
            return false;
        }
        double twiceArea = 0;
        for (int i = 0; i < this.vertices.length; i += 2) {
            double x = this.vertices[i];
            double z = this.vertices[i + 1];
            if (!Double.isFinite(x) || !Double.isFinite(z) || Math.abs(x) > 30000000 || Math.abs(z) > 30000000) {
                return false;
            }
            int next = (i + 2) % this.vertices.length;
            twiceArea += x * this.vertices[next + 1] - this.vertices[next] * z;
        }
        return Math.abs(twiceArea) >= 1;
    }
}
