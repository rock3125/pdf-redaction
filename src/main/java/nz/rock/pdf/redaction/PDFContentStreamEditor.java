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

public class PDFContentStreamEditor extends PDFTextStripper {

    private final PDDocument document;
    private ContentStreamWriter replacement = null;
    private boolean inOperator = false;

    // Tracks if we are inside a nested form
    private int formDepth = 0;

    public PDFContentStreamEditor(PDDocument document) {
        this.document = document;
    }

    protected void nextOperation(Operator operator, List<COSBase> operands) {
        // Do nothing
    }

    protected void write(ContentStreamWriter contentStreamWriter, Operator operator, List<COSBase> operands) throws IOException {
        if (contentStreamWriter != null) {
            contentStreamWriter.writeTokens(operands);
            contentStreamWriter.writeToken(operator);
        }
    }

    @Override
    public void processPage(PDPage page) throws IOException {
        PDStream stream = new PDStream(document);
        try (OutputStream replacementStream = stream.createOutputStream(COSName.FLATE_DECODE)) {
            replacement = new ContentStreamWriter(replacementStream);
            super.processPage(page);
        } finally {
            replacement = null;
        }
        page.setContents(stream);
    }

    @Override
    public void showForm(PDFormXObject form) throws IOException {
        // We MUST allow descending so PDFBox parses the inner images!
        formDepth++;
        super.showForm(form);
        formDepth--;
    }

    private boolean shouldDropForm(PDFormXObject form) {
        if (!(this instanceof PDFRedactor redactor) || !redactor.isRedact()) {
            return false;
        }

        try {
            PDRectangle bbox = form.getBBox();
            if (bbox == null) return false;

            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            Matrix formMatrix = form.getMatrix();

            // Combine the form's internal matrix with the page's current transformation matrix
            if (formMatrix != null) {
                ctm = formMatrix.multiply(ctm);
            }

            // Transform the 4 corners of the bounding box to global page coordinates
            GeneralPath path = new GeneralPath();
            path.moveTo(bbox.getLowerLeftX(), bbox.getLowerLeftY());
            path.lineTo(bbox.getUpperRightX(), bbox.getLowerLeftY());
            path.lineTo(bbox.getUpperRightX(), bbox.getUpperRightY());
            path.lineTo(bbox.getLowerLeftX(), bbox.getUpperRightY());
            path.closePath();

            java.awt.Shape globalShape = ctm.createAffineTransform().createTransformedShape(path);
            Rectangle2D globalBounds = globalShape.getBounds2D();

            // Check if the form's global bounds intersect any redaction region
            return redactor.matchesRegion(globalBounds);

        } catch (Exception e) {
            System.err.println("Error calculating form bounding box: " + e.getMessage());
            return false;
        }
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {

        // Check for XObjects
        if ("Do".equals(operator.getName()) && !operands.isEmpty() && operands.get(0) instanceof COSName name) {
            PDResources resources = getResources();
            if (resources != null) {
                PDXObject xobject = resources.getXObject(name);

                if (xobject instanceof PDFormXObject pdForm) {
                    // If the vector group touches our redaction zone, DROP IT entirely
                    if (shouldDropForm(pdForm)) {
                        System.out.println("Dropped vector form container: " + name.getName());
                        return; // Skip writing this operator to the stream!
                    }

                    // Otherwise, it's safe. Unpack it to look for nested images/forms
                    showForm(pdForm);
                }
                else if (xobject instanceof PDImageXObject) {
                    // It's a standard raster image, modify its pixels
                    if (this instanceof PDFRedactor redactor) {
                        redactor.drawImage(name);
                    }
                }
            }
        }

        // ... [Keep your standard stream processing exactly the same here]
        if (inOperator) {
            super.processOperator(operator, operands);
        } else {
            inOperator = true;
            nextOperation(operator, operands);
            super.processOperator(operator, operands);
            if (formDepth == 0 && replacement != null) {
                write(replacement, operator, operands);
            }
            inOperator = false;
        }
    }

}
