package nz.rock.pdf.redaction;

import java.awt.geom.Rectangle2D;

/**
 * a data structure for holding user request redaction data
 */
public class RectangleAndPage {

    public final Rectangle2D rectangle; // the area to redact inside the PDF (text or image)
    public final int page; // the page inside the PDF (starting at 0) this redaction applies to
    public final boolean isText; // a boolean flag indicating whether it is a piece of text we're redacting

    public RectangleAndPage(int page, boolean isText, Rectangle2D rectangle) {
        this.page = page;
        this.isText = isText;
        this.rectangle = rectangle;
    }

    // return the region to redact
    public Rectangle2D rectangle() {
        return rectangle;
    }

}
