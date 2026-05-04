package nz.co.rock.pdfredactor;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

/**
 * Evaluates the content stream to find images, calculates their rendered bounds,
 * and overwrites overlapping regions directly in the image stream.
 */
class ImageRedactor extends PDFGraphicsStreamEngine {

    private final List<Rectangle2D> redactionBoxes;
    private final PDPage page;
    private final PDDocument document; // Added document reference

    protected ImageRedactor(PDDocument document, PDPage page, List<Rectangle2D> redactionBoxes) {
        super(page);
        this.page = page;
        this.redactionBoxes = redactionBoxes;
        this.document = document;
    }

    @Override
    public void drawImage(PDImage image) throws IOException {
        Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();

        // Calculate image bounding box on the page
        float x = ctm.getTranslateX();
        float y = ctm.getTranslateY();
        float width = ctm.getScaleX();
        float height = ctm.getScaleY();

        Rectangle2D imageBounds = new Rectangle2D.Float(x, y, width, height);

        boolean modified = false;
        BufferedImage bufferedImage = null;

        for (Rectangle2D box : redactionBoxes) {
            if (imageBounds.intersects(box)) {
                if (bufferedImage == null) {
                    bufferedImage = image.getImage(); // Extract raster
                }

                // Calculate intersection relative to the page
                Rectangle2D intersection = imageBounds.createIntersection(box);

                // Map intersection back to image raster coordinates
                float scaleX = bufferedImage.getWidth() / width;
                float scaleY = bufferedImage.getHeight() / height;

                int rasterX = (int) ((intersection.getX() - x) * scaleX);
                // Invert Y because raster origin is top-left, PDF is bottom-left
                int rasterY = (int) ((imageBounds.getMaxY() - intersection.getMaxY()) * scaleY);
                int rasterW = (int) (intersection.getWidth() * scaleX);
                int rasterH = (int) (intersection.getHeight() * scaleY);

                // Draw black box on the image raster
                Graphics2D g2d = bufferedImage.createGraphics();
                g2d.setColor(Color.BLACK);
                g2d.fillRect(rasterX, rasterY, rasterW, rasterH);
                g2d.dispose();

                modified = true;
            }
        }

        if (modified) {
            // Replace the image in the page resources with the newly redacted image
            PDImageXObject newImage = LosslessFactory.createFromImage(document, bufferedImage);

            // Find the COSName under which this image is registered and overwrite it
            for (COSName name : page.getResources().getXObjectNames()) {
                if (page.getResources().isImageXObject(name)) {
                    if (page.getResources().getXObject(name).getCOSObject() == image.getCOSObject()) {
                        page.getResources().put(name, newImage);
                        break;
                    }
                }
            }
        }
    }

    // Required overrides for PDFGraphicsStreamEngine (No-ops for our use case)
    @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {}
    @Override public void clip(int windingRule) {}
    @Override public void moveTo(float x, float y) {}
    @Override public void lineTo(float x, float y) {}
    @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {}
    @Override public Point2D getCurrentPoint() { return new Point2D.Float(0, 0); }
    @Override public void closePath() {}
    @Override public void endPath() {}
    @Override public void strokePath() {}
    @Override public void fillPath(int windingRule) {}
    @Override public void fillAndStrokePath(int windingRule) {}
    @Override public void shadingFill(COSName shadingName) {}
}
