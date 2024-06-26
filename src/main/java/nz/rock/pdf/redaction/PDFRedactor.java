package nz.rock.pdf.redaction;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.*;


/**
 * Redact images and text from a PDF document using PDFBox
 * usage:
 * instantiate     : PDFRedactor r = new PDFRedactor(pdfBoxDocument, true);
 * add rectangles  : r.addRegion(0, 100f, 100f, 200.0f, 200.0f);
 * set text        : r.setTextRedactionList(listOf("earthworks", "roofing", "farm", "external"));
 * run             : r.apply();
 * save the result : document.save(File("..blah"));
 *
 */
public class PDFRedactor extends PDFContentStreamEditor {

    private static final List<String> TEXT_SHOWING_OPERATORS = Arrays.asList("Tj", "'", "\"", "TJ");

    // the PDF document
    private final PDDocument document;
    // redact=true => draw black rectangles and no text/images
    // redact=false => draw all text and images and red rectangles showing the redaction points
    private final boolean redact;

    // the regions to remove
    private final List<RectangleAndPage> regions = new ArrayList<>();
    // the operator text gathered temporarily
    private final List<TextPosition> operatorText = new ArrayList<>();
    // list of all text to censor
    private final List<String> textRedactionList = new ArrayList<>();

    // from the text gatherer - all the text exists on all the pages
    private final HashMap<Integer, List<TextPosition>> textByPage = new HashMap<>();
    // names of images to censor - internal working
    private final List<COSName> intersectingImageNameList = new ArrayList<>();


    /**
     * @param document the pdf document to censor
     * @param redact if true, draw black squares and remove the text, otherwise just draw red rectangles indicating
     *               where the redactions would be
     */
    public PDFRedactor(PDDocument document, boolean redact) throws IOException {
        super(document);
        this.document = document;
        this.redact = redact;
        setup();
    }


    /**
     * helper for constructors
     */
    private void setup() throws IOException {
        addOperator(new PDFDrawObjectExt(this));

        // first get the text positions of all the pages
        PDFTextGatherer gatherer = new PDFTextGatherer(document);
        gatherer.apply();

        // and copy these items locally
        for (int page = 0; page < document.getNumberOfPages(); page++) {
            textByPage.put(page, gatherer.getTextPositionsForPage(page));
        }
    }


    /**
     * run the stripper, create additional rectangles for all the text to be censored
     * per page, and run getText() at the end to run the algorithms over all pages
     *
     */
    public void apply() throws IOException {

        // discover all the text that needs to be redacted for each page
        for (int page = 0; page < document.getNumberOfPages(); page++) {
            List<TextPosition> pageList = textByPage.get(page);

            // turn the text into a string
            StringBuilder sb = new StringBuilder();
            for (TextPosition tp : pageList) {
                String ch = tp.getUnicode();
                sb.append(ch);
            }
            String pageText = sb.toString().toLowerCase();

            // look for each string inside this page list and record censors
            for (String text : textRedactionList) {
                String textLower = text.toLowerCase();
                int textLength = textLower.length();

                int offset = pageText.indexOf(textLower);
                while (offset >= 0) {
                    // is this text valid?
                    char ch = ' ';
                    if (offset + textLength < pageText.length()) {
                        ch = pageText.charAt(offset + textLength);
                    }
                    // valid ending of a word?
                    if (!(ch >= 'a' && ch <= 'z' || ch >= '0' && ch <= '9')) {
                        // add a rectangle to cover the text, so it is censored
                        TextPosition first = pageList.get(offset);
                        TextPosition last = pageList.get(offset + textLength - 1);
                        float w = (last.getX() - first.getX()) + last.getWidth();
                        float h = first.getHeight() * 1.1f;
                        regions.add(new RectangleAndPage(page, true, new Rectangle2D.Float(first.getX(), first.getPageHeight() - first.getY(), w, h)));
                    }
                    offset = pageText.indexOf(textLower, offset + textLength);
                }
            }

        }

        // run
        this.getText(document);
    }


    /**
     * add a region that needs to be redacted
     *
     * @param page the page on which to redact, starting at 0
     * @param x the x on the page
     * @param y the y on the page
     * @param w the width of the rectangle to censor inside
     * @param h the height of the rectangle to censor inside
     */
    public void addRegion(int page, float x, float y, float w, float h) {
        PDPage pdPage = document.getPage(page);
        float height = pdPage.getMediaBox().getHeight();
        regions.add(new RectangleAndPage(page, false, new Rectangle2D.Float(x, height - y, w, -h)));
    }


    /**
     * set the list of text items to redact
     *
     * @param textList the list of text items
     */
    public void setTextRedactionList(List<String> textList) {
        textRedactionList.addAll(textList);
    }


    /**
     * see if the text character from TextPosition matches the region sets
     * in this stripper (for redaction) return true if it does, meaning the text
     * is redacted through a region
     *
     * @param text the text to check (unicode character with position box)
     * @return true if it is to be redacted
     */
    protected boolean matchesRegion(TextPosition text) {
        if (!redact) // in this special mode we never remove text
            return false;

        for (RectangleAndPage location : regions) {
            // wrong page?
            if (location.page != getCurrentPageNo() - 1) {
                continue;
            }

            // text inside any of the given regions?
            Rectangle2D rect = location.rectangle;
            if (rect.intersects(text.getX(), text.getPageHeight() - text.getY(), text.getWidth(), text.getHeight())) {
                return true;
            }
            if (rect.contains(text.getX() + text.getWidth(), text.getPageHeight() - text.getY())) {
                return true;
            }
        }
        return false;
    }


    /**
     * see if an image's box matches any of the regions we're to censor
     * if so, return true
     *
     * @param box the box region to check against all censor regions
     * @return true if this item matches a censor region, otherwise false
     */
    protected boolean matchesRegion(Rectangle2D box) {
        for (RectangleAndPage location : regions) {
            if (location.page != getCurrentPageNo() - 1) {
                continue;
            }

            Rectangle2D rect = location.rectangle;
            // they must intersect, but one must not contain the other
            if (rect.intersects(box.getX(), box.getY(), box.getWidth(), box.getHeight()) && !box.contains(rect)) {
                return true;
            }
        }
        return false;
    }


    @Override
    protected void nextOperation(Operator operator, List<COSBase> operands) {
        operatorText.clear();
        intersectingImageNameList.clear();

        super.nextOperation(operator, operands);
    }


    @Override
    protected void processTextPosition(TextPosition text) {
        operatorText.add(text);
        super.processTextPosition(text);
    }


    /**
     * the stream gets a write instruction - new item is written to a page
     *
     * @param contentStreamWriter   the stream receiving
     * @param operator              the thing being done (e.g. Text adjustment, image draw, etc)
     * @param operands              the details of that thing
     */
    @Override
    protected void write(ContentStreamWriter contentStreamWriter, Operator operator, List<COSBase> operands) throws IOException {
        String operatorString = operator.getName();

        // is this a text character?
        if (TEXT_SHOWING_OPERATORS.contains(operatorString)) {

            boolean operatorHasTextToBeRemoved = false;
            boolean operatorHasTextToBeKept = false;

            // go through all the text of this operator (text item)
            for (TextPosition text : operatorText) {
                boolean textToBeRemoved = matchesRegion(text);
                operatorHasTextToBeRemoved |= textToBeRemoved;
                operatorHasTextToBeKept |= !textToBeRemoved;
            }

            if (operatorHasTextToBeRemoved) {
                if (!operatorHasTextToBeKept) {
                    // Remove at all
                    return;

                } else {
                    if (OperatorName.SHOW_TEXT.equals(operator.getName())) {
                        // single string
                        patchShowTextOperation(contentStreamWriter, operatorText, operands);
                        return;

                    } else if (OperatorName.SHOW_TEXT_ADJUSTED.equals(operator.getName())) {
                        // array of strings
                        patchShowTextAdjustedOperation(contentStreamWriter, operatorText, operands);
                        return;

                    } else if (OperatorName.SHOW_TEXT_LINE.equals(operator.getName())) {
                        // add a new line first
                        super.write(contentStreamWriter, Operator.getOperator(OperatorName.NEXT_LINE), new ArrayList<>());
                        // then process it is an array of strings
                        patchShowTextOperation(contentStreamWriter, operatorText, operands);
                        return;

//                    } else if (OperatorName.SHOW_TEXT_LINE_AND_SPACE.equals(operator.getName())) {
//                        // todo: read the two character spacings
//                        // add a new line first
//                        super.write(contentStreamWriter, Operator.getOperator(OperatorName.NEXT_LINE), new ArrayList<>());
//                        // then process it is an array of strings
//                        patchShowTextOperation(contentStreamWriter, operatorText, operands);
//                        return;

                    } else {
                        return; // remove any other kind of text
                    }
                }
            }

        } else if (OperatorName.DRAW_OBJECT.equals(operator.getName())) {

            // is this image on the shit-list?
            COSBase base0 = operands.get(0);
            if (!(base0 instanceof COSName name)) {
                return;
            }
            // if it is - just ignore it - don't draw it
            if (intersectingImageNameList.contains(name)) {
                return;
            }
        }

        // everything else is passed to the stream
        super.write(contentStreamWriter, operator, operands);
    }


    /**
     * helper for patchShowTextAdjustedOperation below - convert operands from an array to a list
     *
     * @param contentStreamWriter   the stream to write to
     * @param operatorText          the text positions to write out
     * @param operands              the operands of the operation
     */
    protected void patchShowTextOperation(ContentStreamWriter contentStreamWriter,
                                          List<TextPosition> operatorText,
                                          List<COSBase> operands) throws IOException {
        List<COSBase> newOperands = Collections.singletonList(new COSArray(operands));
        patchShowTextAdjustedOperation(contentStreamWriter, operatorText, newOperands);
    }


    /**
     * Censor a text stream based on regions and write text that isn't to be
     * censored to the content-stream
     *
     * @param contentStreamWriter   the stream to write to
     * @param operatorText          the text positions to write out
     * @param operands              the operands of the operation
     */
    protected void patchShowTextAdjustedOperation(ContentStreamWriter contentStreamWriter,
                                                  List<TextPosition> operatorText,
                                                  List<COSBase> operands) throws IOException {
        List<COSBase> newOperandsArray = new ArrayList<>();

        List<TextPosition> texts = new ArrayList<>(operatorText);
        COSArray operandsArray = (COSArray) operands.get(0);
        int textIndex = 0;
        float offset = 0.0f;
        float firstOffset = 0.0f;
        for (COSBase operand : operandsArray.toList()) {
            if (operand instanceof COSNumber) {
                offset += ((COSNumber) operand).floatValue();

            } else if (operand instanceof COSString) {
                byte[] textBytes = ((COSString) operand).getBytes();
                PDFont font = getGraphicsState().getTextState().getFont();

                InputStream in = new ByteArrayInputStream(textBytes);
                int numberOfCharacters = 0;
                while (in.available() > 0) {
                    font.readCode(in);
                    numberOfCharacters++;
                }
                int bytesPerCharacter = textBytes.length / numberOfCharacters;

                int from = 0;
                while (from < numberOfCharacters) {
                    TextPosition text = texts.get(textIndex);

                    // censor or not
                    if (matchesRegion(text)) {
                        if (firstOffset == 0.0f) {
                            firstOffset = text.getX();
                        }
                        int characterCode = operatorText.get(textIndex).getCharacterCodes()[0];
                        offset -= font.getWidth(characterCode);
                        from++;
                        textIndex++;

                    } else {
                        if (offset != 0) {
                            newOperandsArray.add(new COSFloat(offset));
                            offset = 0;
                        }

                        ByteArrayOutputStream textRange = new ByteArrayOutputStream();
                        int to = from;
                        while (to < numberOfCharacters && !matchesRegion(texts.get(textIndex))) {
                            int characterCode = operatorText.get(textIndex).getCharacterCodes()[0];
                            byte[] charBytes = new byte[bytesPerCharacter];
                            for (int i = 0; i < bytesPerCharacter; i++) {
                                charBytes[bytesPerCharacter - 1 - i] = (byte) ((characterCode % 256) & 0xff);
                                characterCode /= 256;
                            }
                            textRange.write(charBytes);
                            to++;
                            textIndex++;
                        }

                        newOperandsArray.add(new COSString(textRange.toByteArray()));

                        from = to;
                    }
                }
            }
        }

        List<COSBase> newOperands = Collections.singletonList(new COSArray(newOperandsArray));
        super.write(contentStreamWriter, Operator.getOperator(OperatorName.SHOW_TEXT_ADJUSTED), newOperands);
    }


    /**
     * call back from PDFDrawObjectEx - inspect an image, and record its COSName if it is to be redacted
     *
     * @param name the name of the image - draw it if we're allowed to (i.e. the image is not redacted)
     */
    public void drawImage(COSName name) {
        // get the page transform
        Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();

        float x = ctm.getTranslateX();
        float y = ctm.getTranslateY();

        float scaleX = ctm.getScaleX();
        float scaleY = ctm.getScaleY();

        Rectangle2D imageLocation = new Rectangle2D.Float(x, y, scaleX, scaleY);
        if (matchesRegion(imageLocation)) {
            intersectingImageNameList.add(name);
        }
    }


    /**
     * helper - change coordinates and width height according to the rotation of the PDF
     *
     * @param rect      the existing rectangle
     * @param pageData  some info about the page's dimensions
     *
     * @return a new rect, or the old one for 0 degree rotations
     */
    private Rectangle2D transform(Rectangle2D rect, PageData pageData) {
        // no transform
        float bottom = pageData.height - (float)(rect.getY() + rect.getHeight());
        float left = (float)rect.getX();
        float w = (float)rect.getWidth();
        float h = (float)rect.getHeight();

        if (pageData.rotate == 90) {
            // x, y, w, h
            return new Rectangle2D.Float(bottom, left, h, w);

        } else if (pageData.rotate == 180) {
            // x, y, w, h
            return new Rectangle2D.Float(pageData.width - left - w, bottom, w, h);

        } else if (pageData.rotate == 270) {
            // x, y, w, h
            return new Rectangle2D.Float(
                    pageData.width - bottom,
                    pageData.height - left,
                    -h, -w);
        }

        // no transform
        return rect;
    }

    /**
     * helper - check if we're on the right page (return true if so) and draw a rect
     *
     * @param pageContentStream the stream to draw into
     * @param location          the page / rect to draw
     * @return true if we drew a rect, otherwise false if we were on the wrong page
     */
    private boolean checkPageAndDrawRect(PDPageContentStream pageContentStream,
                                         RectangleAndPage location,
                                         PageData pageData) throws IOException {
        if (getCurrentPageNo() - 1 != location.page) {
            return false;
        }

        Rectangle2D region = transform(location.rectangle, pageData);

        pageContentStream.moveTo((float) region.getX(), (float) region.getY());
        pageContentStream.lineTo((float) region.getX(), (float) (region.getY() + region.getHeight()));
        pageContentStream.lineTo((float) (region.getX() + region.getWidth()), (float) (region.getY() + region.getHeight()));
        pageContentStream.lineTo((float) (region.getX() + region.getWidth()), (float) region.getY());
        pageContentStream.lineTo((float) region.getX(), (float) region.getY());

        return true;
    }


    private static class PageData {
        public int rotate;
        public float width;
        public float height;

        public PageData(int rotate, float width, float height) {
            this.rotate = rotate;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * Process a page - create a stream and append the boxes for redaction
     *
     * @param page          the pdf page to process
     */
    @Override
    public void processPage(PDPage page) throws IOException {
        super.processPage(page);

        PageData pageData = new PageData(
                page.getCOSObject().getInt(COSName.ROTATE),
                page.getMediaBox().getWidth(), page.getMediaBox().getHeight()
        );

        PDPageContentStream pageContentStream = new PDPageContentStream(this.document, page, PDPageContentStream.AppendMode.APPEND, true);

        if (redact) {
            // if in redact mode, we draw black filled rectangles
            pageContentStream.setStrokingColor(Color.BLACK);
            for (RectangleAndPage location : regions) {
                if (checkPageAndDrawRect(pageContentStream, location, pageData)) {
                    pageContentStream.fill();
                }
            }
            pageContentStream.stroke();

        } else {
            // otherwise we draw red clear rectangles over the text to indicate what might be censored
            pageContentStream.setStrokingColor(Color.RED);
            for (RectangleAndPage location : regions) {
                checkPageAndDrawRect(pageContentStream, location, pageData);
            }
            pageContentStream.stroke();
        }
        pageContentStream.close();
    }


}

