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

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfRedactionTest {

    private final PdfRedaction redactor = new PdfRedaction();

    @Test
    void testRedactionAPI() throws IOException {
        // Setup: Create a dummy PDF in memory with rotation
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            page.setRotation(90); // Test the rotation requirement
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(100, 700);
                cs.showText("This is a confidential document containing SECRET_WORD.");
                cs.endText();
            }

            String text1 = extractTextFromPDF(doc).trim();
            assertTrue(text1.contains("confidential"));
            assertTrue(text1.contains("SECRET_WORD"));

            // Test the API
            redactor.redact(
                    doc,
                    Arrays.asList("confidential", "SECRET_WORD"),
                    Collections.singletonList(new RectangleOnPage(1, 10, 10, 100, 100))
            );

            String text2 = extractTextFromPDF(doc).trim();

            // The target words should be gone
            assertFalse(text2.contains("confidential"));
            assertFalse(text2.contains("SECRET_WORD"));

            // But the rest of the sentence should still be there
            assertTrue(text2.contains("This is a"));
            assertTrue(text2.contains("document containing"));
        }
    }

    @Test
    void testImageRedaction1() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            // 1. Create a 200x200 solid RED image in memory
            BufferedImage bi = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = bi.createGraphics();
            g2d.setColor(Color.RED);
            g2d.fillRect(0, 0, 200, 200);
            g2d.dispose();

            PDImageXObject pdImage = LosslessFactory.createFromImage(doc, bi);

            // 2. Draw the image on the PDF at coordinates (100, 100)
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(pdImage, 100, 100, 200, 200);
            }

            ImagePixelAnalyzer analyzer = new ImagePixelAnalyzer();
            long redPixels1 = analyzer.countPixels(doc, 0xff0000);
            assertEquals(40_000, redPixels1); // 200 x 200

            // 3. Redact a 50x50 area directly over the center of the image
            // Coordinates: x=175, y=175. This overlaps the image drawn at x=100, y=100.
            redactor.redact(
                    doc,
                    null, // No text to redact
                    Collections.singletonList(new RectangleOnPage(1, 175, 175, 50, 50))
            );

            long redPixels2 = analyzer.countPixels(doc, 0xff0000);

            // In-memory verification: Ensure document remains valid and stream is parsed
            // Strict visual assertion of the black pixels requires PDFRenderer,
            // but this ensures the redaction logic runs cleanly in-memory.
            assertEquals(1, doc.getNumberOfPages());
            assertEquals(40_000 - 2_500, redPixels2); // (200 x 200) - (50 x 50)
        }
    }

    @Test
    void testImageRedaction2() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            page.setRotation(90);
            doc.addPage(page);

            // 1. Create a 200x200 solid RED image in memory
            BufferedImage bi = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = bi.createGraphics();
            g2d.setColor(Color.RED);
            g2d.fillRect(0, 0, 200, 200);
            g2d.dispose();

            PDImageXObject pdImage = LosslessFactory.createFromImage(doc, bi);

            // 2. Draw the image on the PDF at coordinates (100, 100)
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(pdImage, 100, 100, 200, 200);
            }

            ImagePixelAnalyzer analyzer = new ImagePixelAnalyzer();
            long redPixels1 = analyzer.countPixels(doc, 0xff0000);
            assertEquals(40_000, redPixels1); // 200 x 200

            // 3. Redact a 50x50 area directly over the center of the image
            // Coordinates: x=175, y=175. This overlaps the image drawn at x=100, y=100.
            redactor.redact(
                    doc,
                    null, // No text to redact
                    Collections.singletonList(new RectangleOnPage(1, 175, 175, 50, 50))
            );

            long redPixels2 = analyzer.countPixels(doc, 0xff0000);

            // In-memory verification: Ensure document remains valid and stream is parsed
            // Strict visual assertion of the black pixels requires PDFRenderer,
            // but this ensures the redaction logic runs cleanly in-memory.
            assertEquals(1, doc.getNumberOfPages());
            assertEquals(40_000 - 2_500, redPixels2); // (200 x 200) - (50 x 50)

            //savePDF(doc, "redacted-image.pdf");
        }
    }

    @Test
    void testRotation0Degrees() throws IOException {
        runRotationTest(0, "The quick brown fox jumps over the CONFIDENTIAL fence.");
    }

    @Test
    void testRotation90Degrees() throws IOException {
        runRotationTest(90, "The quick brown fox jumps over the CONFIDENTIAL fence.");
    }

    @Test
    void testRotation180Degrees() throws IOException {
        runRotationTest(180, "The quick brown fox jumps over the CONFIDENTIAL fence.");
    }

    @Test
    void testRotation270Degrees() throws IOException {
        runRotationTest(270, "The quick brown fox jumps over the CONFIDENTIAL fence.");
    }

    // test redaction in a 180 degree rotated pdf
    @Test
    public void testOcrPdfWith180DegreesRotation() throws IOException {
        // load our test PDF
        byte[] bytes = loadBinary("/rotated_180_area.pdf");
        assertTrue(bytes.length > 0);
        PDDocument doc = Loader.loadPDF(bytes);

        String textBefore = extractTextFromPDF(doc);
        assertTrue(textBefore.contains("richiesta"));

        redactor.redact(
                doc,
                List.of("richiesta"),
                null
        );

        String textAfter = extractTextFromPDF(doc);
        assertFalse(textAfter.contains("richiesta"));
    }

    // test the redaction of a multipage pdf with vector images
    @Test
    public void testRedactionOfSaratogaFile() throws IOException {
        byte[] bytes = loadBinary("/saratoga.pdf");
        assertTrue(bytes.length > 0);
        PDDocument doc = Loader.loadPDF(bytes);

        List<String> myList = Arrays.asList("earthworks", "roofing", "external");

        String textBefore = extractTextFromPDF(doc);
        for (String word : myList) {
            assertTrue(textBefore.contains(word), "Word " + word + " should be present in the PDF");
        }

        redactor.redact(
                doc,
                myList,
                null
        );

        //savePDF(doc, "test1.pdf");

        String textAfter = extractTextFromPDF(doc);
        for (String word : myList) {
            assertFalse(textAfter.contains(word), "Word " + word + " should be removed from the PDF");
        }
    }

    @Test
    public void testTelephoneNumberRedaction() throws IOException {
        // load our test PDF
        String number = "+1-602-373-2455";
        byte[] bytes = loadBinary("/telephone-number.pdf");
        assertTrue(bytes.length > 0);
        PDDocument doc = Loader.loadPDF(bytes);
        String textBefore = extractTextFromPDF(doc);
        assertTrue(textBefore.contains(number));

        List<RectangleOnPage> rectangles = redactor.getRedactionRectangles(doc, Collections.singletonList(number));
        assertEquals(1, rectangles.size());
        RectangleOnPage rect = rectangles.get(0);
        assertEquals(1, rect.getPage());
        assertTrue(rect.getX() > 0);
        assertTrue(rect.getY() > 0);
        assertTrue(rect.getWidth() > 80);
        assertTrue(rect.getHeight() > 10);

        redactor.redact(
                doc,
                Collections.singletonList(number),
                null
        );

        String textAfter = extractTextFromPDF(doc);
        assertFalse(textAfter.contains(number));

        List<RectangleOnPage> rectangles2 = redactor.getRedactionRectangles(doc, Collections.singletonList(number));
        assertTrue(rectangles2.isEmpty());

        doc.close();
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Reusable logic to test text redaction across various rotations in memory.
     */
    private void runRotationTest(int rotation, String text) throws IOException {
        try (PDDocument doc = createTextPdf(rotation, text)) {

            // Pre-condition: Check the word exists
            String textBefore = extractTextFromPDF(doc);
            assertTrue(textBefore.contains("CONFIDENTIAL"));

            // Redact the word "CONFIDENTIAL"
            redactor.redact(
                    doc,
                    Collections.singletonList("CONFIDENTIAL"),
                    null
            );

            // Post-condition: Verify the output
            String textAfter = extractTextFromPDF(doc);

            assertTrue(textAfter.contains("The quick brown fox jumps over the"),
                    "Standard text should remain intact at " + rotation + " degrees.");

            assertFalse(textAfter.contains("CONFIDENTIAL"),
                    "Redacted word should be scrubbed from the stream at " + rotation + " degrees.");

            //savePDF(doc, "rotation-" + rotation + ".pdf");
        }
    }

    /**
     * Helper to create a PDF with specific rotation and text.
     */
    private PDDocument createTextPdf(int rotation, String text) throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage();
        page.setRotation(rotation);
        doc.addPage(page);

        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            cs.newLineAtOffset(200, 400);
            cs.showText(text);
            cs.endText();
        }
        return doc;
    }

    /**
     * Helper method to extract all text from a given in-memory PDDocument.
     */
    private String extractTextFromPDF(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        return stripper.getText(document);
    }

    private void savePDF(PDDocument document, String filename) throws IOException {
        File tempOut = new File(filename);
        document.save(tempOut);
    }

    /**
     * load a binary from resources
     *
     * @param resourceFilename the name of the file in the resource
     * @return a byte array with the data
     */
    private byte[] loadBinary(String resourceFilename) throws IOException {
        assertNotNull(resourceFilename);
        try (InputStream inputStream = PdfRedactionTest.class.getResourceAsStream(resourceFilename)) {
            if (inputStream == null)
                throw new FileNotFoundException(resourceFilename);
            return inputStream.readAllBytes();
        }
    }

}
