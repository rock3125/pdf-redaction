package nz.rock.pdf.redaction;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;


/**
 * stream processor
 *
 * take a PDF stream and allow it to be edited using callbacks
 *
 */
public class PDFContentStreamEditor extends PDFTextStripper {

    private final PDDocument document;                  // the document
    private ContentStreamWriter replacement = null;     // the replacement stream
    private boolean inOperator = false;                 // detected nested objects

    public PDFContentStreamEditor(PDDocument document) throws IOException{
        this.document = document;
    }

    /**
     * <p>
     * This method retrieves the next operation before its registered
     * listener is called. The default does nothing.
     * </p>
     * <p>
     * Override this method to retrieve state information from before the
     * operation execution.
     * </p>
     */
    protected void nextOperation(Operator operator, List<COSBase> operands) {
        // Do nothing
    }

    /**
     * <p>
     * This method writes content stream operations to the target canvas. The default
     * implementation writes them as they come, so it essentially generates identical
     * copies of the original instructions {@link #processOperator(Operator, List)}
     * forwards to it.
     * </p>
     * <p>
     * Override this method to achieve some fancy editing effect.
     * </p>
     */
    protected void write(ContentStreamWriter contentStreamWriter, Operator operator, List<COSBase> operands) throws IOException {
        contentStreamWriter.writeTokens(operands);
        contentStreamWriter.writeToken(operator);
    }

    // Actual editing methods
    @Override
    public void processPage(PDPage page) throws IOException {
        PDStream stream = new PDStream(document);
        OutputStream replacementStream;
        replacement = new ContentStreamWriter(replacementStream = stream.createOutputStream(COSName.FLATE_DECODE));
        super.processPage(page);
        replacementStream.close();
        page.setContents(stream);
        replacement = null;
    }

    // PDFStreamEngine overrides to allow editing
    @Override
    public void showForm(PDFormXObject form) throws IOException {
        // DON'T descend into XObjects
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
        if (inOperator) {
            super.processOperator(operator, operands);
        } else {
            inOperator = true;
            nextOperation(operator, operands);
            super.processOperator(operator, operands);
            write(replacement, operator, operands);
            inOperator = false;
        }
    }

}
