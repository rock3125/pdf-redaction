package nz.rock.pdf.redaction;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;


/**
 * Gather all text positions inside a PDF document
 *
 */
public class PDFTextGatherer extends PDFContentStreamEditor {

    // the PDF document
    private final PDDocument document;
    // the text positions seen
    private final HashMap<Integer, List<TextPosition>> textByPage = new HashMap<>();


    public PDFTextGatherer(PDDocument document) throws IOException {
        super(document);
        this.document = document;
    }

    @Override
    protected void processTextPosition(TextPosition text) {
        int page = getCurrentPageNo() - 1;
        if (!textByPage.containsKey(page)) {
            textByPage.put(page, new ArrayList<>());
        }
        textByPage.get(page).add(text);
        super.processTextPosition(text);
    }


    // run the text gatherer
    public void apply() throws IOException {
        this.getText(document);
    }


    /**
     * get the text for a given page - return empty if dne
     *
     * @param page the page, starting at 0
     * @return the text of that page, or empty string
     */
    public String getPageText(int page) {
        List<TextPosition> textPositions = textByPage.get(page);
        if (textPositions != null) {
            StringBuilder sb = new StringBuilder();
            for (TextPosition textPosition : textPositions) {
                sb.append(textPosition.getUnicode());
            }
            return sb.toString();
        }
        return "";
    }


    /**
     * get all the text positions for a given page (zero offset)
     *
     * @param page the page, starting at zero
     * @return an empty list if no text, otherwise the text positions for the entire page
     */
    public List<TextPosition> getTextPositionsForPage(int page) {
        if (!textByPage.containsKey(page)) {
            return Collections.emptyList();
        }
        return textByPage.get(page);
    }

}

