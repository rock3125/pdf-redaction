package nz.rock.pdf.redaction;

import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.DrawObject;
import org.apache.pdfbox.contentstream.operator.MissingOperandException;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;

import java.io.IOException;
import java.util.List;

/**
 * Draw object interceptor
 *
 * override process of DrawObject and get the object - draw it if it is of type PDImageXObject
 * otherwise pass it back to DrawObject (super)
 *
 */
public class PDFDrawObjectExt extends DrawObject {

    public PDFDrawObjectExt(PDFRedactor context) {
        super(context);
    }

    /**
     * image callback
     *
     * @param operator the kind of operation for the image
     * @param arguments the image name and its other parameters
     */
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
        ((PDFRedactor) context).drawImage(name);
    }

}
