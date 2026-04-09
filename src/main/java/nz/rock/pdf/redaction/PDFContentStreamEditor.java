package nz.rock.pdf.redaction;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;

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

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
        // 1. Natively intercept the "Do" (Draw Object) operator to bypass PDFTextStripper's strict filters
        if ("Do".equals(operator.getName()) && !operands.isEmpty() && operands.get(0) instanceof COSName name) {
            PDResources resources = getResources();
            if (resources != null) {
                PDXObject xobject = resources.getXObject(name);

                if (xobject instanceof PDFormXObject pdForm) {
                    // Force the engine to unpack the inner form
                    showForm(pdForm);

                } else if (xobject instanceof PDImageXObject) {
                    // Trigger the redaction
                    if (this instanceof PDFRedactor redactor) {
                        redactor.drawImage(name);
                    }
                }
            }
        }

        // 2. Proceed with standard stream processing
        if (inOperator) {
            super.processOperator(operator, operands);
        } else {
            inOperator = true;
            nextOperation(operator, operands);

            super.processOperator(operator, operands);

            // ONLY write to the replacement stream if we are on the main page.
            // This prevents inner form tokens from corrupting the page stream!
            if (formDepth == 0 && replacement != null) {
                write(replacement, operator, operands);
            }

            inOperator = false;
        }
    }

}
