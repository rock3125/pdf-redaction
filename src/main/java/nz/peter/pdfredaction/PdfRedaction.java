/*
 * Copyright (c) 2026 by Rock de Vocht
 *
 * All rights reserved. No part of this publication may be reproduced, distributed, or
 * transmitted in any form or by any means, including photocopying, recording, or other
 * electronic or mechanical methods, without the prior written permission of the publisher,
 * except in the case of brief quotations embodied in critical reviews and certain other
 * noncommercial uses permitted by copyright law.
 *
 */

package nz.peter.pdfredaction;

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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A utility to redact text, coordinates, and overlapping images inside a PDF document.
 */
public class PdfRedaction {

    /**
     * Redacts specified words and/or rectangular areas from a PDF document by removing or masking
     * text and images within the specified regions across all pages.
     *
     * @param document The PDF document to be redacted.
     * @param words A list of words to be redacted from the document. Can be null or empty if
     *              only specific areas are being redacted.
     * @param areas A list of rectangular areas, each specifying a page and coordinates, to
     *              be redacted. Can be null or empty if only word-based redactions are
     *              performed. Each rectangle should correspond to a specific 1-based page number.
     * @param outlineOnly If true, only outline rectangles will be drawn over the redacted areas
     *                    and no actual redaction will occur, for visual inspection.
     * @throws IOException If an error occurs while processing the PDF document.
     */
    public void redact(
            PDDocument document,
            List<String> words,
            List<RectangleOnPage> areas,
            boolean outlineOnly
    ) throws IOException {
        // null? - just ignore it
        if (document == null) return;
        // nothing to redact?
        if (words == null && areas == null) return;

        int numPages = document.getNumberOfPages();

        for (int i = 0; i < numPages; i++) {
            int pageNum = i + 1;
            PDPage page = document.getPage(i);
            if (page == null) continue;

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
                WordFinder textStripper = new WordFinder(page, words);
                textStripper.setStartPage(pageNum);
                textStripper.setEndPage(pageNum);
                textStripper.getText(document);
                pageRedactionBoxes.addAll(textStripper.getFoundBoundingBoxes());
            }

            // nothing to redact?
            if (pageRedactionBoxes.isEmpty()) {
                continue;
            }

            // Scrub text from the content stream (Font-Aware & Kerning-Aware)
            if (!outlineOnly && words != null && !words.isEmpty()) {
                scrubTextTokens(document, page, words);
            }

            // Redact overlapping images
            if (!outlineOnly) {
                ImageRedactor imageRedactor = new ImageRedactor(document, page, pageRedactionBoxes);
                imageRedactor.processPage(page);
            }

            if (outlineOnly) {
                // Draw outline magenta/purple rectangles over the redacted areas
                drawPurpleRectangles(document, page, pageRedactionBoxes);
            } else {
                // Draw the physical black boxes over the redacted areas
                drawBlackBoxes(document, page, pageRedactionBoxes);
            }

            // Clear the document's metadata
            clearMetadata(document);
        }
    }


    /**
     * Extracts a list of rectangular regions on specific pages of a PDF document where specified words are located.
     * These regions can be used for redaction purposes.
     *
     * @param document The PDF document to analyze. If null, an empty list is returned.
     * @param words A list of words to locate in the document. If null or empty, no regions are located, and an empty list is returned.
     * @return A list of {@link RectangleOnPage} objects, where each object specifies the 1-based page number and
     *         the coordinates of a rectangular region containing one of the specified words. If no matches are found or
     *         inputs are invalid, an empty list is returned.
     * @throws IOException If an error occurs while processing the PDF document.
     */
    public List<RectangleOnPage> getRedactionRectangles(PDDocument document, List<String> words) throws IOException {
        // null? - just ignore it
        if (document == null) return Collections.emptyList();
        // nothing to redact?
        if (words == null || words.isEmpty()) return Collections.emptyList();

        int numPages = document.getNumberOfPages();
        ArrayList<RectangleOnPage> pageRectangles = new ArrayList<>();

        for (int i = 0; i < numPages; i++) {
            int pageNum = i + 1;
            PDPage page = document.getPage(i);
            if (page == null) continue;

            // Find bounding boxes for the requested words
            WordFinder textStripper = new WordFinder(page, words);
            textStripper.setStartPage(pageNum);
            textStripper.setEndPage(pageNum);
            textStripper.getText(document);
            for (Rectangle2D boundingBox : textStripper.getFoundBoundingBoxes()) {
                RectangleOnPage rop = new RectangleOnPage(
                        pageNum,
                        (float)boundingBox.getX(),
                        (float)boundingBox.getY(),
                        (float)boundingBox.getWidth(),
                        (float)boundingBox.getHeight()
                );
                pageRectangles.add(rop);
            }
        }
        return pageRectangles;
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

        // Same word-boundary rule as WordFinder, so the scrubbed text always matches the drawn
        // boxes. Whitespace in a search phrase is matched flexibly (\s*) because inter-word
        // spacing is often just a positioning gap with no actual space glyph in the stream.
        List<Pattern> scrubPatterns = new ArrayList<>();
        for (String word : wordsToScrub) {
            if (word != null && !word.trim().isEmpty()) {
                StringBuilder regex = new StringBuilder("(?<!\\w)");
                String[] parts = word.trim().split("\\s+");
                for (int p = 0; p < parts.length; p++) {
                    if (p > 0) regex.append("\\s*");
                    regex.append(Pattern.quote(parts[p]));
                }
                regex.append("(?!\\w)");
                scrubPatterns.add(Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE));
            }
        }

        PDResources resources = page.getResources();

        // Producers routinely split single words - and even single glyph runs - across
        // positioning operators (Td/Tm), across text objects (BT..ET), and across TJ kerning
        // adjustments. Flushing the match buffer at any of those would leave the split word
        // unmatched, and therefore silently unredacted, in the stream. So instead of flushing
        // on operators, we track the text matrix and pen position, and only flush the buffer
        // when a SHOW operation actually starts on a different baseline (a new visual line).
        // Same-line gaps wider than a fraction of the font size become a synthetic space so
        // words from different columns or tab stops never fuse together.
        ScrubState st = new ScrubState();
        st.patterns = scrubPatterns;

        for (int i = 0; i < tokens.size(); i++) {
            Object token = tokens.get(i);

            if (token instanceof Operator op) {
                String opName = op.getName();

                switch (opName) {
                    case "Tf" -> {
                        if (resources != null) {
                            Object nameTok = i >= 2 ? tokens.get(i - 2) : null;
                            if (nameTok instanceof COSName fontName) {
                                st.font = resources.getFont(fontName);
                            }
                            Float size = numberAt(tokens, i - 1);
                            if (size != null) st.fontSize = size;
                        }
                    }
                    case "BT" -> {
                        st.tm = new float[]{1, 0, 0, 1, 0, 0};
                        st.penX = 0;
                    }
                    case "ET" -> {
                        st.tm = null; // buffer intentionally kept - lines may span text objects
                    }
                    case "Tm" -> {
                        Float a = numberAt(tokens, i - 6);
                        Float b = numberAt(tokens, i - 5);
                        Float c = numberAt(tokens, i - 4);
                        Float d = numberAt(tokens, i - 3);
                        Float e = numberAt(tokens, i - 2);
                        Float f = numberAt(tokens, i - 1);
                        if (a != null && b != null && c != null && d != null && e != null && f != null) {
                            st.tm = new float[]{a, b, c, d, e, f};
                        } else {
                            st.tm = null;
                        }
                        st.penX = 0;
                    }
                    case "Td", "TD" -> {
                        Float tx = numberAt(tokens, i - 2);
                        Float ty = numberAt(tokens, i - 1);
                        if (tx != null && ty != null && st.tm != null) {
                            translateTm(st, tx, ty);
                            if ("TD".equals(opName)) st.leading = -ty;
                        } else {
                            st.tm = null;
                        }
                        st.penX = 0;
                    }
                    case "T*" -> {
                        if (st.tm != null) translateTm(st, 0, -st.leading);
                        st.penX = 0;
                    }
                    case "TL" -> {
                        Float l = numberAt(tokens, i - 1);
                        if (l != null) st.leading = l;
                    }
                    case "Tc" -> {
                        Float v = numberAt(tokens, i - 1);
                        if (v != null) st.charSpacing = v;
                    }
                    case "Tw" -> {
                        Float v = numberAt(tokens, i - 1);
                        if (v != null) st.wordSpacing = v;
                    }
                    case "Tz" -> {
                        Float v = numberAt(tokens, i - 1);
                        if (v != null) st.hScale = v / 100f;
                    }
                    default -> { /* other operators do not affect text position tracking */ }
                }
            } else if (token instanceof COSString cosString) {
                // Peek: ' and " move to the next line BEFORE showing their operand string
                Object next = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
                if (next instanceof Operator nextOp
                        && ("'".equals(nextOp.getName()) || "\"".equals(nextOp.getName()))) {
                    if ("\"".equals(nextOp.getName())) {
                        Float aw = numberAt(tokens, i - 2);
                        Float ac = numberAt(tokens, i - 1);
                        if (aw != null) st.wordSpacing = aw;
                        if (ac != null) st.charSpacing = ac;
                    }
                    if (st.tm != null) translateTm(st, 0, -st.leading);
                    st.penX = 0;
                }
                showString(cosString, st);

            } else if (token instanceof COSArray array) {
                for (int j = 0; j < array.size(); j++) {
                    COSBase element = array.get(j);
                    if (element instanceof COSString cosString) {
                        showString(cosString, st);
                    } else if (element instanceof org.apache.pdfbox.cos.COSNumber num) {
                        // TJ adjustment: moves the pen; a large gap is a word separator
                        float shift = -num.floatValue() / 1000f * st.fontSize * st.hScale;
                        st.penX += shift;
                        if (Math.abs(shift) > WORD_GAP_EM * st.fontSize && !st.blockText.isEmpty()) {
                            st.blockText.append(' ');
                        }
                    }
                }
            }
        }

        // Catch any remaining text at the end of the stream
        processTextSequence(st.activeTokens, st.blockText, scrubPatterns);

        // Rewrite the modified tokens back to the page stream
        PDStream newContents = new PDStream(document);
        try (OutputStream out = newContents.createOutputStream(COSName.FLATE_DECODE)) {
            ContentStreamWriter tokenWriter = new ContentStreamWriter(out);
            tokenWriter.writeTokens(tokens); // Modifications were done in-place
        }
        page.setContents(newContents);
    }


    /** Same-line gaps wider than this fraction of the font size become a synthetic space. */
    private static final float WORD_GAP_EM = 0.15f;
    /** Two shows whose baselines differ by less than this are considered the same line. */
    private static final float BASELINE_TOLERANCE = 1.0f;

    /**
     * Returns the numeric operand at the given token index, or null if the index is
     * out of range or the token is not a number.
     */
    private Float numberAt(List<Object> tokens, int index) {
        if (index < 0 || index >= tokens.size()) return null;
        if (tokens.get(index) instanceof org.apache.pdfbox.cos.COSNumber num) {
            return num.floatValue();
        }
        return null;
    }

    /** Applies a text-space translation (Td/TD/T*) to the tracked text matrix. */
    private void translateTm(ScrubState st, float tx, float ty) {
        st.tm[4] += tx * st.tm[0] + ty * st.tm[2];
        st.tm[5] += tx * st.tm[1] + ty * st.tm[3];
    }

    /**
     * Handles one show operation (the string of Tj/'/" or one element of a TJ array).
     * Flushes the buffered line when this show starts on a different baseline, inserts a
     * synthetic space when it continues the line after a word-sized gap, then appends the
     * decoded string to the buffer.
     */
    private void showString(COSString cosString, ScrubState st) {
        if (cosString == null) return;

        if (st.tm == null) {
            // Position unknown: process whatever is buffered, then buffer this string alone
            flushLine(st);
            processCOSString(cosString, st.font, st.activeTokens, st.blockText);
            return;
        }

        // Device-space start point of this show (the pen may have advanced since tm was set)
        float startX = st.tm[4] + st.penX * st.tm[0];
        float startY = st.tm[5] + st.penX * st.tm[1];

        boolean sameLine = false;
        if (st.lineBasis != null
                && st.lineBasis[0] == st.tm[0] && st.lineBasis[1] == st.tm[1]
                && st.lineBasis[2] == st.tm[2] && st.lineBasis[3] == st.tm[3]) {
            if (st.tm[1] == 0 && st.tm[0] != 0) {
                // horizontal text: the baseline coordinate is f
                sameLine = Math.abs(st.tm[5] - st.lineCoord) < BASELINE_TOLERANCE;
            } else if (st.tm[0] == 0 && st.tm[1] != 0) {
                // vertical (90/270 rotated) text: the baseline coordinate is e
                sameLine = Math.abs(st.tm[4] - st.lineCoord) < BASELINE_TOLERANCE;
            }
        }

        if (!sameLine) {
            flushLine(st);
            st.lineBasis = st.tm.clone();
            st.lineCoord = (st.tm[1] == 0) ? st.tm[5] : st.tm[4];
        } else if (st.haveExpected && !st.blockText.isEmpty()) {
            // Same line: synthesize a space if the gap since the previous show is word-sized
            float gapDevice;
            float scale;
            if (st.tm[1] == 0) {
                gapDevice = startX - st.expectedX;
                scale = st.tm[0];
            } else {
                gapDevice = startY - st.expectedY;
                scale = st.tm[1];
            }
            float gapText = gapDevice / scale;
            float fontSize = st.fontSize > 0 ? st.fontSize : 10f;
            if (Math.abs(gapText) > WORD_GAP_EM * fontSize) {
                st.blockText.append(' ');
            }
        }

        processCOSString(cosString, st.font, st.activeTokens, st.blockText);

        st.penX += textAdvance(cosString, st);
        st.expectedX = st.tm[4] + st.penX * st.tm[0];
        st.expectedY = st.tm[5] + st.penX * st.tm[1];
        st.haveExpected = true;
    }

    /** Processes and clears the buffered line. */
    private void flushLine(ScrubState st) {
        processTextSequence(st.activeTokens, st.blockText, st.patterns);
        st.activeTokens.clear();
        st.blockText.setLength(0);
        st.lineBasis = null;
        st.haveExpected = false;
    }

    /**
     * Best-effort text-space advance of a shown string (glyph widths, char/word spacing,
     * horizontal scaling). Only used to decide whether a gap is a word separator, so an
     * inaccurate width cannot corrupt the output stream.
     */
    private float textAdvance(COSString s, ScrubState st) {
        if (st.font == null) return 0;
        float total = 0;
        try {
            InputStream in = new ByteArrayInputStream(s.getBytes());
            while (in.available() > 0) {
                int code = st.font.readCode(in);
                float width;
                try {
                    width = st.font.getWidth(code);
                } catch (Exception ex) {
                    width = 500f;
                }
                total += width / 1000f * st.fontSize + st.charSpacing + (code == 32 ? st.wordSpacing : 0);
            }
        } catch (Exception e) {
            // best effort - see javadoc
        }
        return total * st.hScale;
    }

    /**
     * Mutable text state tracked while walking a content stream during scrubbing.
     */
    private static class ScrubState {
        List<Pattern> patterns;
        PDFont font;
        float fontSize = 0;
        float charSpacing = 0;
        float wordSpacing = 0;
        float hScale = 1;
        float leading = 0;
        float[] tm;                 // current text matrix {a,b,c,d,e,f}; null when unknown
        float penX = 0;             // text-space advance accumulated since tm was last set

        List<TokenRef> activeTokens = new ArrayList<>();
        StringBuilder blockText = new StringBuilder();
        float[] lineBasis;          // a,b,c,d of the buffered line; null when buffer is fresh
        float lineCoord;            // baseline coordinate (f for horizontal, e for vertical)
        float expectedX, expectedY; // device point where a continuation show would start
        boolean haveExpected;
    }

    /**
     * Processes a COSString object by decoding its contents using the provided font,
     * appending the decoded text to the provided StringBuilder, and creating a
     * mapping between the COSString and its decoded text. The mapping is stored in
     * a list of TokenRef objects for further processing.
     *
     * @param cosString The COSString object to be processed. Represents the original
     *                  tokenized content from the PDF document.
     * @param font The PDFont object used to decode the COSString. Must not be null.
     * @param activeTokens A list of TokenRef objects that will be updated with a
     *                     reference to the decoded content of the COSString.
     * @param blockText A StringBuilder that accumulates the decoded text content of
     *                  all processed COSStrings for the current context.
     */
    private void processCOSString(
            COSString cosString,
            PDFont font,
            List<TokenRef> activeTokens,
            StringBuilder blockText
    ) {
        // check for nulls
        if (font == null || cosString == null || activeTokens == null || blockText == null) return;

        try {
            // Read character codes sequentially, remembering the byte span of each code so that
            // redacted glyphs can later be cut out of the original bytes without re-encoding
            // (font.encode() fails on many subset fonts, so we never rely on it).
            byte[] bytes = cosString.getBytes();
            InputStream in = new ByteArrayInputStream(bytes);
            StringBuilder decodedBuilder = new StringBuilder();
            List<int[]> codeSpans = new ArrayList<>();
            List<Integer> charToCode = new ArrayList<>();

            while (in.available() > 0) {
                int spanStart = bytes.length - in.available();
                int code = font.readCode(in);
                int spanEnd = bytes.length - in.available();
                if (spanEnd == spanStart) break; // no progress - avoid an endless loop

                int codeIndex = codeSpans.size();
                codeSpans.add(new int[]{spanStart, spanEnd});

                String unicode = font.toUnicode(code);
                if (unicode != null && !unicode.isEmpty()) {
                    decodedBuilder.append(unicode);
                    for (int k = 0; k < unicode.length(); k++) {
                        charToCode.add(codeIndex);
                    }
                }
            }

            String decoded = decodedBuilder.toString();

            if (!decoded.isEmpty()) {
                TokenRef ref = new TokenRef();
                ref.token = cosString;
                ref.originalBytes = bytes;
                ref.codeSpans = codeSpans;
                ref.charToCode = new int[charToCode.size()];
                for (int k = 0; k < charToCode.size(); k++) {
                    ref.charToCode[k] = charToCode.get(k);
                }
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

    /**
     * Processes a text sequence by identifying and scrubbing specified words from a block of text
     * and updating corresponding token references to reflect these changes.
     *
     * @param activeTokens A list of TokenRef objects representing the origin stream tokens for
     *                     chunks of text. These tokens are analyzed and updated to remove portions
     *                     of text that match any words to be scrubbed.
     * @param blockText A StringBuilder containing the combined text extracted from the tokens.
     *                  This is used as the primary source for identifying occurrences of the
     *                  words to scrub.
     * @param wordsToScrub A list of strings representing the words to be scrubbed from the text
     *                     sequence. Matching is performed in a case-insensitive manner, and tokens
     *                     containing matched words are updated to reflect the redaction.
     */
    private void processTextSequence(
            List<TokenRef> activeTokens,
            StringBuilder blockText,
            List<Pattern> scrubPatterns
    ) {
        // null check
        if (activeTokens == null || blockText == null || scrubPatterns == null) return;
        if (activeTokens.isEmpty() || blockText.isEmpty()) return;

        String text = blockText.toString();

        for (Pattern pattern : scrubPatterns) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                int startMatch = matcher.start();
                int endMatch = matcher.end();

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
            }
        }

        // Rebuild each modified token from the original bytes, dropping the byte spans of the
        // glyph codes whose characters were flagged. This keeps untouched glyphs byte-identical
        // and never needs font.encode(), which fails on many subset fonts.
        for (TokenRef ref : activeTokens) {
            boolean[] removeCode = new boolean[ref.codeSpans.size()];
            boolean modified = false;

            for (int k = 0; k < ref.chars.length; k++) {
                if (ref.chars[k] == '\uFFFF') {
                    removeCode[ref.charToCode[k]] = true;
                    modified = true;
                }
            }

            if (modified) {
                ByteArrayOutputStream survivingBytes = new ByteArrayOutputStream();
                for (int c = 0; c < ref.codeSpans.size(); c++) {
                    if (!removeCode[c]) {
                        int[] span = ref.codeSpans.get(c);
                        survivingBytes.write(ref.originalBytes, span[0], span[1] - span[0]);
                    }
                }
                ref.token.setValue(survivingBytes.toByteArray());
            }
        }
    }

    /**
     * Draws filled black rectangles on the specified PDF page within the provided rectangular areas.
     * Each rectangle is drawn using the coordinates, width, and height specified in the {@link Rectangle2D} objects.
     *
     * @param document The PDF document containing the page to which the black boxes will be added.
     * @param page The specific page of the PDF document where the black boxes will be drawn.
     * @param boxes A list of {@link Rectangle2D} objects defining the positions and dimensions of the black rectangles to be drawn.
     *              Each rectangle is represented in the coordinate system of the page.
     * @throws IOException If an error occurs while accessing the PDF document or writing to the page.
     */
    private void drawBlackBoxes(PDDocument document, PDPage page, List<Rectangle2D> boxes) throws IOException {
        if (document == null || page == null || boxes == null) return;
        try (PDPageContentStream contentStream =
                     new PDPageContentStream(
                             document,
                             page,
                             PDPageContentStream.AppendMode.APPEND,
                             true,
                             true
                     )
        ) {
            contentStream.setNonStrokingColor(0, 0, 0);
            for (Rectangle2D box : boxes) {
                contentStream.addRect((float) box.getX(), (float) box.getY(), (float) box.getWidth(), (float) box.getHeight());
                contentStream.fill();
            }
        }
    }

    /**
     * Draws outline magenta/purle rectangles on the specified PDF page within the provided rectangular areas.
     * Each rectangle is drawn using the coordinates, width, and height specified in the {@link Rectangle2D} objects.
     *
     * @param document The PDF document containing the page to which the black boxes will be added.
     * @param page The specific page of the PDF document where the black boxes will be drawn.
     * @param boxes A list of {@link Rectangle2D} objects defining the positions and dimensions of the meganta/purple rectangles to be drawn.
     *              Each rectangle is represented in the coordinate system of the page.
     * @throws IOException If an error occurs while accessing the PDF document or writing to the page.
     */
    private void drawPurpleRectangles(PDDocument document, PDPage page, List<Rectangle2D> boxes) throws IOException {
        if (document == null || page == null || boxes == null) return;
        try (PDPageContentStream contentStream =
                     new PDPageContentStream(
                             document,
                             page,
                             PDPageContentStream.AppendMode.APPEND,
                             true,
                             true
                     )
        ) {
            contentStream.setStrokingColor(0.6f, 0.0f, 0.6f);
            contentStream.setLineWidth(1.0f);
            for (Rectangle2D box : boxes) {
                contentStream.addRect((float) box.getX(), (float) box.getY(), (float) box.getWidth(), (float) box.getHeight());
            }
            contentStream.stroke();
        }
    }

    /**
     * DTO to link continuous text characters back to their exact origin stream tokens.
     */
    private static class TokenRef {
        COSString token;
        byte[] originalBytes;    // the token's raw bytes as parsed from the stream
        List<int[]> codeSpans;   // per glyph code: {startByte, endByte} within originalBytes
        int[] charToCode;        // per decoded char: index into codeSpans it came from
        int globalStart;
        int globalEnd;
        char[] chars;
    }

}
