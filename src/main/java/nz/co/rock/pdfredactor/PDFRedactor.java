package nz.co.rock.pdfredactor;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.contentstream.operator.Operator;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A professional utility to redact text, coordinates, and overlapping images from a PDF document.
 */
public class PDFRedactor {

    public void redact(PDDocument document, List<String> words, List<RectangleOnPage> areas) throws IOException {
        int numPages = document.getNumberOfPages();

        for (int i = 0; i < numPages; i++) {
            int pageNum = i + 1;
            PDPage page = document.getPage(i);

            List<Rectangle2D> pageRedactionBoxes = new ArrayList<>();

            // Add user-defined coordinate boxes for this page
            if (areas != null) {
                for (RectangleOnPage area : areas) {
                    if (area.getPage() == pageNum) {
                        pageRedactionBoxes.add(new Rectangle2D.Float(area.getX(), area.getY(), area.getWidth(), area.getHeight()));
                    }
                }
            }

            // Find bounding boxes for the requested words
            if (words != null && !words.isEmpty()) {
                WordFinder textStripper = new WordFinder(words);
                textStripper.setStartPage(pageNum);
                textStripper.setEndPage(pageNum);
                textStripper.getText(document);
                pageRedactionBoxes.addAll(textStripper.getFoundBoundingBoxes());
            }

            if (pageRedactionBoxes.isEmpty()) {
                continue;
            }

            // Scrub text from the content stream (Font-Aware & Kerning-Aware)
            if (words != null && !words.isEmpty()) {
                scrubTextTokens(document, page, words);
            }

            // Redact overlapping images
            ImageRedactor imageRedactor = new ImageRedactor(document, page, pageRedactionBoxes);
            imageRedactor.processPage(page);

            // Draw the physical black boxes over the redacted areas
            drawBlackBoxes(document, page, pageRedactionBoxes);

            // Clear the document's metadata
            clearMetadata(document);
        }
    }


    /**
     * Completely removes all legacy and XMP metadata from the document.
     */
    private void clearMetadata(PDDocument document) {
        // Clear the legacy Document Information Dictionary
        // (This holds Author, Title, Creator, Producer, CreationDate, etc.)
        // Replacing it with a brand new, empty object wipes the old dictionary.
        document.setDocumentInformation(new PDDocumentInformation());

        // Clear the modern XMP Metadata Stream
        // Setting this to null removes the entire XML metadata stream from the catalog.
        if (document.getDocumentCatalog() != null) {
            document.getDocumentCatalog().setMetadata(null);
        }
    }


    /**
     * Advanced stream scrubber that decodes CID fonts and stitches kerning arrays
     * to safely target and remove exact words from the underlying stream.
     */
    private void scrubTextTokens(PDDocument document, PDPage page, List<String> wordsToScrub) throws IOException {
        PDFStreamParser parser = new PDFStreamParser(page);
        List<Object> tokens = parser.parse();

        PDResources resources = page.getResources();
        PDFont currentFont = null;

        List<TokenRef> activeTokens = new ArrayList<>();
        StringBuilder blockText = new StringBuilder();

        for (int i = 0; i < tokens.size(); i++) {
            Object token = tokens.get(i);

            if (token instanceof Operator op) {
                String opName = op.getName();

                if ("Tf".equals(opName) && i >= 2) {
                    COSName fontName = (COSName) tokens.get(i - 2);
                    if (resources != null) {
                        currentFont = resources.getFont(fontName);
                    }
                } else if ("BT".equals(opName)) {
                    // Begin Text block: clear the buffer
                    activeTokens.clear();
                    blockText.setLength(0);
                } else if ("ET".equals(opName) || "Td".equals(opName) || "TD".equals(opName) || "Tm".equals(opName) || "T*".equals(opName)) {
                    // End of text block or line break: process the buffered sequence
                    processTextSequence(activeTokens, blockText, wordsToScrub);
                    activeTokens.clear();
                    blockText.setLength(0);
                }
            } else if (token instanceof COSString) {
                processCOSString((COSString) token, currentFont, activeTokens, blockText);

            } else if (token instanceof COSArray array) {
                for (int j = 0; j < array.size(); j++) {
                    COSBase element = array.get(j);
                    if (element instanceof COSString) {
                        processCOSString((COSString) element, currentFont, activeTokens, blockText);
                    }
                }
            }
        }

        // Catch any remaining text at the end of the stream
        processTextSequence(activeTokens, blockText, wordsToScrub);

        // Rewrite the modified tokens back to the page stream
        PDStream newContents = new PDStream(document);
        try (OutputStream out = newContents.createOutputStream(COSName.FLATE_DECODE)) {
            ContentStreamWriter tokenWriter = new ContentStreamWriter(out);
            tokenWriter.writeTokens(tokens); // Modifications were done in-place
        }
        page.setContents(newContents);
    }

    private void processCOSString(COSString cosString, PDFont font, List<TokenRef> activeTokens, StringBuilder blockText) {
        if (font == null) return;
        try {
            // FIX: Read character codes sequentially using an InputStream
            InputStream in = new ByteArrayInputStream(cosString.getBytes());
            StringBuilder decodedBuilder = new StringBuilder();

            while (in.available() > 0) {
                int code = font.readCode(in);
                String unicode = font.toUnicode(code);
                if (unicode != null) {
                    decodedBuilder.append(unicode);
                }
            }

            String decoded = decodedBuilder.toString();

            if (!decoded.isEmpty()) {
                TokenRef ref = new TokenRef();
                ref.token = cosString;
                ref.font = font;
                ref.globalStart = blockText.length();
                blockText.append(decoded);
                ref.globalEnd = blockText.length();
                ref.chars = decoded.toCharArray();
                activeTokens.add(ref);
            }
        } catch (Exception e) {
            // Ignore decoding failures for unsupported subsets
        }
    }

    private void processTextSequence(List<TokenRef> activeTokens, StringBuilder blockText, List<String> wordsToScrub) {
        if (activeTokens.isEmpty() || blockText.isEmpty()) return;

        String text = blockText.toString().toLowerCase();

        for (String word : wordsToScrub) {
            String searchWord = word.toLowerCase();
            if (searchWord.isEmpty()) continue;

            int index = text.indexOf(searchWord);
            while (index >= 0) {
                int startMatch = index;
                int endMatch = index + searchWord.length();

                // Find which stream tokens contain the matched characters and flag them for removal
                for (TokenRef ref : activeTokens) {
                    if (ref.globalEnd > startMatch && ref.globalStart < endMatch) {
                        int localStart = Math.max(0, startMatch - ref.globalStart);
                        int localEnd = Math.min(ref.chars.length, endMatch - ref.globalStart);
                        for (int k = localStart; k < localEnd; k++) {
                            ref.chars[k] = '\uFFFF'; // Placeholder for deleted character
                        }
                    }
                }
                index = text.indexOf(searchWord, index + 1);
            }
        }

        // Rebuild and re-encode the surviving characters back into the stream tokens
        for (TokenRef ref : activeTokens) {
            StringBuilder newText = new StringBuilder();
            boolean modified = false;

            for (char c : ref.chars) {
                if (c != '\uFFFF') {
                    newText.append(c);
                } else {
                    modified = true;
                }
            }

            if (modified) {
                try {
                    // Encode the string back into the native font's byte format
                    byte[] newBytes = ref.font.encode(newText.toString());
                    ref.token.setValue(newBytes);
                } catch (Exception e) {
                    // Fallback: If re-encoding fails, entirely blank the token to ensure redaction
                    ref.token.setValue(new byte[0]);
                }
            }
        }
    }

    private void drawBlackBoxes(PDDocument document, PDPage page, List<Rectangle2D> boxes) throws IOException {
        try (PDPageContentStream contentStream = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
            contentStream.setNonStrokingColor(0, 0, 0);
            for (Rectangle2D box : boxes) {
                contentStream.addRect((float) box.getX(), (float) box.getY(), (float) box.getWidth(), (float) box.getHeight());
                contentStream.fill();
            }
        }
    }

    /**
     * DTO to link continuous text characters back to their exact origin stream tokens.
     */
    private static class TokenRef {
        COSString token;
        PDFont font;
        int globalStart;
        int globalEnd;
        char[] chars;
    }

}
