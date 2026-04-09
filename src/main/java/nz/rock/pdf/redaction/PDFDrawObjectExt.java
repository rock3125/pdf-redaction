package nz.rock.pdf.redaction;

import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.DrawObject;
import org.apache.pdfbox.contentstream.operator.MissingOperandException;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.IOException;
import java.util.List;

public class PDFDrawObjectExt extends DrawObject {

    public PDFDrawObjectExt(PDFRedactor context) {
        super(context);
    }

    @Override
    public void process(Operator operator, List<COSBase> arguments) throws IOException {
        if (arguments.isEmpty()) {
            throw new MissingOperandException(operator, arguments);
        }
        COSBase base0 = arguments.get(0);
        if (!(base0 instanceof COSName name)) {
            return;
        }

        PDFStreamEngine context = getContext();
        PDXObject xobject = context.getResources().getXObject(name);

        if (xobject instanceof PDFormXObject pdForm) {
            // It's a container! Explicitly force the engine to process the inner stream
            context.showForm(pdForm);
        } else if (xobject instanceof PDImageXObject) {
            // We found the actual image! Redact it.
            ((PDFRedactor) context).drawImage(name);
        } else {
            // Fallback for other objects
            super.process(operator, arguments);
        }
    }

}
