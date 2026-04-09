package nz.rock.pdf.redaction;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;

import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;


/**
 * Intercepts the PDF content stream to examine forms, images, and text.
 * It allows for real-time filtering or modification of PDF operations (redaction).
 */
public class PDFContentStreamEditor extends PDFTextStripper {

    private final PDDocument document; // the PDF document we're processing
    private ContentStreamWriter replacement = null; // redaction destination stream (create new PDF)
    private boolean inOperator = false;

    // Tracks recursion level when entering nested PDF Form XObjects
    private int formDepth = 0;

    public PDFContentStreamEditor(PDDocument document) {
        this.document = document;
    }

    /**
     * Hook for subclasses to perform logic before an operator is written to the new stream.
     */
    protected void nextOperation(Operator operator, List<COSBase> operands) {
        // Placeholder for custom logic in extending classes
    }

    /**
     * Writes the current PDF operator and its operands to the replacement content stream.
     */
    protected void write(ContentStreamWriter contentStreamWriter, Operator operator, List<COSBase> operands) throws IOException {
        if (contentStreamWriter != null) {
            contentStreamWriter.writeTokens(operands);
            contentStreamWriter.writeToken(operator);
        }
    }

    @Override
    public void processPage(PDPage page) throws IOException {
        // Create a new stream that will eventually replace the existing page content
        PDStream stream = new PDStream(document);
        try (OutputStream replacementStream = stream.createOutputStream(COSName.FLATE_DECODE)) {
            replacement = new ContentStreamWriter(replacementStream);

            // Start the standard PDFBox parsing process
            super.processPage(page);
        } finally {
            replacement = null;
        }

        // Overwrite the page's content with our newly filtered stream
        page.setContents(stream);
    }

    @Override
    public void showForm(PDFormXObject form) throws IOException {
        // Increment depth so we know we are inside a nested XObject
        formDepth++;
        // Standard PDFBox processing: this will trigger processOperator for the form's internal stream
        super.showForm(form);
        formDepth--;
    }

    /**
     * Determines if a Form XObject (vector group) should be removed based on its location.
     */
    private boolean shouldDropForm(PDFormXObject form) {
        // Ensure the current instance is a redactor and redaction is enabled
        if (!(this instanceof PDFRedactor redactor) || !redactor.isRedact()) {
            return false;
        }

        try {
            PDRectangle bbox = form.getBBox();
            if (bbox == null) return false;

            // Get the CTM (Current Transformation Matrix) to understand where the form sits on the page
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            Matrix formMatrix = form.getMatrix();

            // If the form has its own internal transformation, combine it with the page CTM
            if (formMatrix != null) {
                ctm = formMatrix.multiply(ctm);
            }

            // Define the bounding box path in the form's local coordinate space
            GeneralPath path = new GeneralPath();
            path.moveTo(bbox.getLowerLeftX(), bbox.getLowerLeftY());
            path.lineTo(bbox.getUpperRightX(), bbox.getLowerLeftY());
            path.lineTo(bbox.getUpperRightX(), bbox.getUpperRightY());
            path.lineTo(bbox.getLowerLeftX(), bbox.getUpperRightY());
            path.closePath();

            // Transform the local coordinates into global page coordinates (usually points/pixels)
            java.awt.Shape globalShape = ctm.createAffineTransform().createTransformedShape(path);
            Rectangle2D globalBounds = globalShape.getBounds2D();

            // Check if this area overlaps with any user-defined redaction zones
            return redactor.matchesRegion(globalBounds);

        } catch (Exception e) {
            System.err.println("Error calculating form bounding box: " + e.getMessage());
            return false;
        }
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {

        // Handle 'Do' operators (Draws an external object like an Image or a Form)
        if ("Do".equals(operator.getName()) && !operands.isEmpty() && operands.get(0) instanceof COSName name) {
            PDResources resources = getResources();
            if (resources != null) {
                PDXObject xobject = resources.getXObject(name);
                if (xobject instanceof PDFormXObject pdForm) {
                    // If the entire vector group is inside a redaction zone, drop the 'Do' command entirely
                    if (shouldDropForm(pdForm)) {
                        System.out.println("Dropped vector form container: " + name.getName());
                        return; // Exit early: this operator is NOT written to the new stream
                    }

                    // If not dropped, we descend into the form to check individual elements inside it
                    showForm(pdForm);
                }
                else if (xobject instanceof PDImageXObject) {
                    // If it's a raster image, delegate to the redactor to mask the pixel data
                    if (this instanceof PDFRedactor redactor) {
                        redactor.drawImage(name);
                    }
                }
            }
        }

        // --- Stream Reconstruction Logic ---

        // This flag prevents infinite recursion if super.processOperator calls back into this method
        if (inOperator) {
            super.processOperator(operator, operands);

        } else {
            inOperator = true;

            // Execute custom subclass logic
            nextOperation(operator, operands);

            // Allow PDFBox to update its internal state (graphics state, text position, etc.)
            super.processOperator(operator, operands);

            // If we are at the top level (not inside a Form XObject being unpacked),
            // write the current operator to our new page content stream.
            if (formDepth == 0 && replacement != null) {
                write(replacement, operator, operands);
            }

            inOperator = false;
        }
    }

}
