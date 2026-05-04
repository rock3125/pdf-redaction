package nz.co.rock.pdfredactor;

/**
 * Represents a specific area on a specific page to be redacted.
 */
public class RectangleOnPage {
    private final int page;
    private final float x;
    private final float y;
    private final float width;
    private final float height;

    /**
     * @param page   1-based page number.
     * @param x      X coordinate (bottom-left origin).
     * @param y      Y coordinate (bottom-left origin).
     * @param width  Width of the rectangle.
     * @param height Height of the rectangle.
     */
    public RectangleOnPage(int page, float x, float y, float width, float height) {
        this.page = page;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public int getPage() { return page; }
    public float getX() { return x; }
    public float getY() { return y; }
    public float getWidth() { return width; }
    public float getHeight() { return height; }
}
