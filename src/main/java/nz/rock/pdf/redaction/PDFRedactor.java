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

package nz.rock.pdf.redaction;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;

import java.awt.*;
import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.*;


/**
 * Core Redaction Engine.
 * This class identifies text and images that overlap with specific regions or match specific keywords,
 * then physically removes/modifies them from the PDF content stream.
 */
public class PDFRedactor extends PDFContentStreamEditor {

    // PDF Operators responsible for rendering text on a page
    private static final List<String> TEXT_SHOWING_OPERATORS = Arrays.asList("Tj", "'", "\"", "TJ");

    private final PDDocument document;

    // If true: Replaces text/images with black boxes (Permanent).
    // If false: Outlines areas in red for debugging/preview purposes.
    private final boolean redact;

    // Stores geographical areas (Rectangles) flagged for redaction
    private final List<RectangleAndPage> regions = new ArrayList<>();

    // Temporary storage for text positions during a single stream operation
    private final List<TextPosition> operatorText = new ArrayList<>();

    // List of sensitive words/phrases to search for and redact
    private final List<String> textRedactionList = new ArrayList<>();

    // Map to hold every character's position on every page (populated during setup)
    private final HashMap<Integer, List<TextPosition>> textByPage = new HashMap<>();


    public PDFRedactor(PDDocument document, boolean redact) throws IOException {
        super(document);
        this.document = document;
        this.redact = redact;
        setup();
    }

    // are we in full redact mode, or just drawing outline boxes?
    public boolean isRedact() {
        return redact;
    }

    /**
     * Initializes the redactor by pre-scanning the document for all text positions.
     */
    private void setup() throws IOException {
        // Use a custom gatherer to find where every single letter is located
        PDFTextGatherer gatherer = new PDFTextGatherer(document);
        gatherer.apply();

        for (int page = 0; page < document.getNumberOfPages(); page++) {
            textByPage.put(page, gatherer.getTextPositionsForPage(page));
        }
    }

    /**
     * The main execution loop.
     * 1. Scans text for keyword matches.
     * 2. Calculates bounding boxes for matches.
     * 3. Triggers the stream editor to rewrite the PDF.
     */
    public void apply() throws IOException {
        for (int page = 0; page < document.getNumberOfPages(); page++) {
            List<TextPosition> pageList = textByPage.get(page);
            if (pageList == null || pageList.isEmpty()) continue;

            StringBuilder sb = new StringBuilder();
            // Tracks which character in the StringBuilder maps to which TextPosition in the PDF
            List<Integer> stringIndexToTextPositionIndex = new ArrayList<>();

            for (int i = 0; i < pageList.size(); i++) {
                TextPosition tp = pageList.get(i);

                // Insert synthetic spaces to ensure "word" detection works across PDF stream breaks
                if (i > 0) {
                    TextPosition prev = pageList.get(i - 1);
                    boolean isLineBreak = Math.abs(tp.getY() - prev.getY()) > prev.getHeight() * 0.5f;
                    boolean isSpace = (tp.getX() - (prev.getX() + prev.getWidth())) > (prev.getWidthOfSpace() * 0.5f);

                    if (isLineBreak || isSpace) {
                        sb.append(' ');
                        stringIndexToTextPositionIndex.add(i);
                    }
                }

                String ch = tp.getUnicode();
                sb.append(ch);
                for (int j = 0; j < ch.length(); j++) {
                    stringIndexToTextPositionIndex.add(i);
                }
            }

            String pageText = sb.toString().toLowerCase();

            // Search the extracted text for each keyword in our redaction list
            for (String text : textRedactionList) {
                String textLower = text.toLowerCase();
                int textLength = textLower.length();

                int offset = pageText.indexOf(textLower);
                while (offset >= 0) {

                    // Boundary check: ensure we aren't redacting "farm" inside "farmer"

                    // Check trailing character boundary
                    boolean validEnd = true;
                    if (offset + textLength < pageText.length()) {
                        char endCh = pageText.charAt(offset + textLength);
                        // If the next character is a letter or a digit, it's NOT a valid word end
                        validEnd = !Character.isLetterOrDigit(endCh);
                    }

                    // Check leading character boundary
                    boolean validStart = true;
                    if (offset > 0 && offset < pageText.length()) {
                        char startCh = pageText.charAt(offset - 1);
                        // If the previous character is a letter or a digit, it's NOT a valid word start
                        validStart = !Character.isLetterOrDigit(startCh);
                    }

                    if (validStart && validEnd) {
                        int firstListIndex = stringIndexToTextPositionIndex.get(offset);
                        int lastListIndex = stringIndexToTextPositionIndex.get(offset + textLength - 1);

                        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
                        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

                        // Calculate the collective bounding box for the entire matched string
                        for (int k = firstListIndex; k <= lastListIndex; k++) {
                            TextPosition pos = pageList.get(k);
                            float px = pos.getX();
                            float py = pos.getPageHeight() - pos.getY(); // Convert to standard Cartesian
                            float pw = pos.getWidth();
                            float ph = pos.getHeight();

                            minX = Math.min(minX, px);
                            minY = Math.min(minY, py);
                            maxX = Math.max(maxX, px + pw);
                            maxY = Math.max(maxY, py + ph);
                        }

                        // Add the calculated region to our "to-be-redacted" list
                        float padding = 1.0f;
                        regions.add(new RectangleAndPage(page, true,
                                new Rectangle2D.Float(minX - padding, minY - padding, (maxX - minX) + (padding * 2), (maxY - minY) + (padding * 2))
                        ));
                    }
                    offset = pageText.indexOf(textLower, offset + textLength);
                }
            }
        }

        // Start the inherited ContentStreamEditor process to rewrite the PDF
        this.getText(document);
    }

    /**
     * Manually defines a rectangular region on a specific page for redaction.
     */
    /**
     * Manually defines a rectangular region on a specific page for redaction.
     * Fixed to account for CropBox offsets and standard PDF coordinate flipping.
     */
    public void addRegion(int page, float x, float y, float w, float h) {
        PDPage pdPage = document.getPage(page);
        if (pdPage == null) return;

        // Use CropBox instead of MediaBox, as it represents the actual visible area
        PDRectangle cropBox = pdPage.getCropBox();
        float boxHeight = cropBox.getHeight();
        float boxXOffset = cropBox.getLowerLeftX();
        float boxYOffset = cropBox.getLowerLeftY();

        // PDFBox Y-axis is bottom-to-top.
        // We subtract the user's Y and Height from the total box height,
        // then add the box's own Y offset (in case the page doesn't start at 0).
        float flippedY = (boxHeight - y - h) + boxYOffset;
        float adjustedX = x + boxXOffset;

        regions.add(new RectangleAndPage(page, false, new Rectangle2D.Float(adjustedX, flippedY, w, h)));
    }

    public void setTextRedactionList(List<String> textList) {
        textRedactionList.addAll(textList);
    }

    /**
     * Checks if a specific character's position overlaps with any redaction regions.
     */
    protected boolean matchesImageRegion(TextPosition text) {
        if (!redact) return false;

        for (RectangleAndPage location : regions) {
            if (location.page != getCurrentPageNo() - 1) continue;

            Rectangle2D rect = location.rectangle;
            // Check if character box intersects or is contained within the redaction box
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
     * Checks if a bounding box (usually for an image) overlaps with redaction regions.
     */
    protected boolean matchesImageRegion(Rectangle2D box) {
        for (RectangleAndPage location : regions) {
            // check this is the right page - if not => skip
            if (location.page != getCurrentPageNo() - 1) continue;
            if (location.isText) continue; // text?  we only do image here
            if (location.rectangle == null) continue;

            Rectangle2D rect = location.rectangle.getBounds2D();
            if (rect.intersects(box.getX(), box.getY(), box.getWidth(), box.getHeight()) && !box.contains(rect)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void nextOperation(Operator operator, List<COSBase> operands) {
        operatorText.clear();
        super.nextOperation(operator, operands);
    }

    @Override
    protected void processTextPosition(TextPosition text) {
        operatorText.add(text); // Buffer text positions for the current operator
        super.processTextPosition(text);
    }

    /**
     * Intercepts the write process. If an operator contains sensitive text,
     * it is modified or skipped before being written to the new content stream.
     */
    @Override
    protected void write(ContentStreamWriter contentStreamWriter, Operator operator, List<COSBase> operands) throws IOException {
        String operatorString = operator.getName();

        if (TEXT_SHOWING_OPERATORS.contains(operatorString)) {
            boolean operatorHasTextToBeRemoved = false;
            boolean operatorHasTextToBeKept = false;

            for (TextPosition text : operatorText) {
                boolean textToBeRemoved = matchesImageRegion(text);
                operatorHasTextToBeRemoved |= textToBeRemoved;
                operatorHasTextToBeKept |= !textToBeRemoved;
            }

            if (operatorHasTextToBeRemoved) {
                if (!operatorHasTextToBeKept) {
                    return; // The entire string is redacted; drop the operator entirely
                } else {
                    // Partial redaction: rewrite the operator to only include safe characters
                    if (OperatorName.SHOW_TEXT.equals(operator.getName())) {
                        patchShowTextOperation(contentStreamWriter, operatorText, operands);
                        return;
                    } else if (OperatorName.SHOW_TEXT_ADJUSTED.equals(operator.getName())) {
                        patchShowTextAdjustedOperation(contentStreamWriter, operatorText, operands);
                        return;
                    } else if (OperatorName.SHOW_TEXT_LINE.equals(operator.getName())) {
                        super.write(contentStreamWriter, Operator.getOperator(OperatorName.NEXT_LINE), new ArrayList<>());
                        patchShowTextOperation(contentStreamWriter, operatorText, operands);
                        return;
                    } else {
                        return;
                    }
                }
            }
        }
        super.write(contentStreamWriter, operator, operands);
    }

    /**
     * Wraps a simple text operation into an adjusted text operation for easier patching.
     */
    protected void patchShowTextOperation(ContentStreamWriter contentStreamWriter,
                                          List<TextPosition> operatorText,
                                          List<COSBase> operands) throws IOException {
        List<COSBase> newOperands = Collections.singletonList(new COSArray(operands));
        patchShowTextAdjustedOperation(contentStreamWriter, operatorText, newOperands);
    }

    /**
     * Logic for partially redacting a text array.
     * It iterates through character codes, stripping those that match a region,
     * and calculating offsets to maintain the visual position of the remaining text.
     */
    protected void patchShowTextAdjustedOperation(ContentStreamWriter contentStreamWriter,
                                                  List<TextPosition> operatorText,
                                                  List<COSBase> operands) throws IOException {
        List<COSBase> newOperandsArray = new ArrayList<>();
        List<TextPosition> texts = new ArrayList<>(operatorText);
        COSArray operandsArray = (COSArray) operands.get(0);

        int textIndex = 0;
        float offset = 0.0f;

        for (COSBase operand : operandsArray.toList()) {
            if (operand instanceof COSNumber) {
                offset += ((COSNumber) operand).floatValue();
            } else if (operand instanceof COSString) {
                byte[] textBytes = ((COSString) operand).getBytes();
                PDFont font = getGraphicsState().getTextState().getFont();

                // Identify how many characters are in this encoded string
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

                    if (matchesImageRegion(text)) {
                        // Character is redacted: effectively replace its width with a gap (offset)
                        int characterCode = operatorText.get(textIndex).getCharacterCodes()[0];
                        offset -= font.getWidth(characterCode);
                        from++;
                        textIndex++;
                    } else {
                        // Character is safe: write it to the output
                        if (offset != 0) {
                            newOperandsArray.add(new COSFloat(offset));
                            offset = 0;
                        }

                        ByteArrayOutputStream textRange = new ByteArrayOutputStream();
                        int to = from;
                        while (to < numberOfCharacters && !matchesImageRegion(texts.get(textIndex))) {
                            int characterCode = operatorText.get(textIndex).getCharacterCodes()[0];
                            byte[] charBytes = new byte[bytesPerCharacter];
                            // Handle multi-byte character encoding
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
     * Redacts portions of a raster image.
     * Unlike text (which is removed from the stream), images are modified by
     * drawing black pixels over the intersecting areas and re-embedding them.
     */
    public void drawImage(COSName name) {
        if (!redact) return;

        try {
            PDResources resources = getResources();
            if (resources == null) return;
            PDXObject xobject = resources.getXObject(name);

            // must be an image
            if (!(xobject instanceof PDImageXObject pdImage)) return;

            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            float x = ctm.getTranslateX();
            float y = ctm.getTranslateY();
            float absWidth = Math.abs(ctm.getScaleX());
            float absHeight = Math.abs(ctm.getScaleY());

            // Establish the image's location on the PDF page
            Rectangle2D imageLocation = new Rectangle2D.Float(
                    ctm.getScaleX() >= 0 ? x : x + ctm.getScaleX(),
                    ctm.getScaleY() >= 0 ? y : y + ctm.getScaleY(),
                    absWidth, absHeight
            );

            BufferedImage awtImage = null;
            Graphics2D g2d = null;
            boolean imageModified = false;

            for (RectangleAndPage location : regions) {
                if (location.page != getCurrentPageNo() - 1) continue;

                Rectangle2D rect = location.rectangle();
                if (rect != null && rect.intersects(imageLocation)) {
                    if (awtImage == null) {
                        awtImage = pdImage.getImage();
                        // Ensure image is in a format that allows drawing (RGB/ARGB)
                        if (awtImage.getType() != BufferedImage.TYPE_INT_ARGB && awtImage.getType() != BufferedImage.TYPE_INT_RGB) {
                            BufferedImage converted = new BufferedImage(awtImage.getWidth(), awtImage.getHeight(), BufferedImage.TYPE_INT_ARGB);
                            Graphics2D g = converted.createGraphics();
                            g.drawImage(awtImage, 0, 0, null);
                            g.dispose();
                            awtImage = converted;
                        }
                        g2d = awtImage.createGraphics();
                        g2d.setColor(Color.BLACK);
                    }

                    Rectangle2D intersection = imageLocation.createIntersection(rect);
                    double pixelScaleX = awtImage.getWidth() / imageLocation.getWidth();
                    double pixelScaleY = awtImage.getHeight() / imageLocation.getHeight();

                    // Convert PDF coordinates to Image pixel coordinates
                    int px = (int) Math.round((intersection.getX() - imageLocation.getX()) * pixelScaleX);
                    int pw = (int) Math.round(intersection.getWidth() * pixelScaleX);
                    int ph = (int) Math.round(intersection.getHeight() * pixelScaleY);
                    int py = (ctm.getScaleY() >= 0)
                            ? (int) Math.round((imageLocation.getMaxY() - intersection.getMaxY()) * pixelScaleY)
                            : (int) Math.round((intersection.getMinY() - imageLocation.getMinY()) * pixelScaleY);

                    g2d.fillRect(px, py, pw, ph);
                    imageModified = true;
                }
            }

            if (imageModified) {
                g2d.dispose();
                // Replace the original image in the PDF resources with the censored version
                PDImageXObject newImage = LosslessFactory.createFromImage(document, awtImage);
                resources.put(name, newImage);
            }

        } catch (Exception e) {
            System.err.println("Failed to redact image overlap: " + e.getMessage());
        }
    }

    /**
     * Handles coordinate transformations for rotated PDF pages.
     */
    private Rectangle2D transform(Rectangle2D rect, PageData pageData) {
        float bottom = pageData.height - (float)(rect.getY() + rect.getHeight());
        float left = (float)rect.getX();
        float w = (float)rect.getWidth();
        float h = (float)rect.getHeight();

        return switch (pageData.rotate) {
            case 90 -> new Rectangle2D.Float(bottom, left, h, w);
            case 180 -> new Rectangle2D.Float(pageData.width - left - w, bottom, w, h);
            case 270 -> new Rectangle2D.Float(pageData.width - bottom, pageData.height - left, -h, -w);
            default -> new Rectangle2D.Float(left, bottom, w, h);
        };
    }

    /**
     * Draws a rectangle into the page content stream (visual redaction).
     */
    private boolean checkPageAndDrawRect(PDPageContentStream pageContentStream,
                                         RectangleAndPage location,
                                         PageData pageData) throws IOException {
        if (getCurrentPageNo() - 1 != location.page) return false;

        Rectangle2D region = location.rectangle.getBounds2D();
        pageContentStream.addRect((float)region.getX(), (float)region.getY(), (float)region.getWidth(), (float)region.getHeight());
        return true;
    }

    private record PageData(int rotate, float width, float height) {}

    /**
     * After processing the content stream, this method appends the visual
     * redaction boxes (black or red) to the top layer of the page.
     */
    @Override
    public void processPage(PDPage page) throws IOException {
        super.processPage(page);

        PageData pageData = new PageData(
                page.getCOSObject().getInt(COSName.ROTATE),
                page.getMediaBox().getWidth(), page.getMediaBox().getHeight()
        );

        // Open a new stream in APPEND mode to draw the redaction overlays
        try (PDPageContentStream pageContentStream = new PDPageContentStream(this.document, page, PDPageContentStream.AppendMode.APPEND, true)) {
            if (redact) {
                pageContentStream.setNonStrokingColor(Color.BLACK);
                for (RectangleAndPage location : regions) {
                    if (checkPageAndDrawRect(pageContentStream, location, pageData)) {
                        pageContentStream.fill();
                    }
                }
            } else {
                pageContentStream.setStrokingColor(Color.RED);
                for (RectangleAndPage location : regions) {
                    if (checkPageAndDrawRect(pageContentStream, location, pageData)) {
                        pageContentStream.stroke();
                    }
                }
            }
        }
    }

}
