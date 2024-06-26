package nz.rock.pdf.redaction;

import java.awt.geom.Rectangle2D;

public class RectangleAndPage {
    public final Rectangle2D rectangle;
    public final int page;
    public final boolean isText;

    public RectangleAndPage(int page, boolean isText, Rectangle2D rectangle) {
        this.page = page;
        this.isText = isText;
        this.rectangle = rectangle;
    }
}
