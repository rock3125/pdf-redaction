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
                    Collections.singletonList(new RectangleOnPage(1, 10, 10, 100, 100)),
                    false
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
                    Collections.singletonList(new RectangleOnPage(1, 175, 175, 50, 50)),
                    false
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
                    Collections.singletonList(new RectangleOnPage(1, 175, 175, 50, 50)),
                    false
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
                null,
                false
        );

        String textAfter = extractTextFromPDF(doc);
        assertFalse(textAfter.contains("richiesta"));
        doc.close();
    }

    // test redaction in a 180 degree rotated pdf
    @Test
    public void testOcrPdfWith180DegreesRotation2() throws IOException {
        // load our test PDF
        byte[] bytes = loadBinary("/rotated_180_area.pdf");
        assertTrue(bytes.length > 0);
        PDDocument doc = Loader.loadPDF(bytes);

        String textBefore = extractTextFromPDF(doc);
        assertTrue(textBefore.contains("richiesta"));

        redactor.redact(
                doc,
                List.of("richiesta"),
                null,
                true
        );
        String textAfter = extractTextFromPDF(doc);
        // not removed
        assertTrue(textAfter.contains("richiesta"));
        //savePDF(doc, "/home/rock/test1.pdf");
        doc.close();
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
                null,
                false
        );

        //savePDF(doc, "test1.pdf");

        String textAfter = extractTextFromPDF(doc);
        for (String word : myList) {
            assertFalse(containsWholeWord(textAfter, word), "Word " + word + " should be removed from the PDF");
        }
        // words merely containing a target ("externally") must NOT be scrubbed
        assertTrue(textAfter.contains("externally"), "'externally' should survive redaction of 'external'");
        doc.close();
    }

    /** whole-word, case-insensitive containment check (same boundary rule as the redactor) */
    private boolean containsWholeWord(String text, String word) {
        return java.util.regex.Pattern
                .compile("(?<!\\w)" + java.util.regex.Pattern.quote(word) + "(?!\\w)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(text).find();
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
                null,
                false
        );

        String textAfter = extractTextFromPDF(doc);
        assertFalse(textAfter.contains(number));

        List<RectangleOnPage> rectangles2 = redactor.getRedactionRectangles(doc, Collections.singletonList(number));
        assertTrue(rectangles2.isEmpty());

        doc.close();
    }

    // test redaction of a person's name in the car insurance policy document
    @Test
    public void testCarInsurancePolicyNameRedaction() throws IOException {
        // load our test PDF
        String name = "Persis Bentote";
        byte[] bytes = loadBinary("/CarInsurancePolicyCIP-211339.pdf");
        assertTrue(bytes.length > 0);
        PDDocument doc = Loader.loadPDF(bytes);

        String textBefore = extractTextFromPDF(doc);
        assertTrue(textBefore.contains(name), "Name should be present in the PDF before redaction");

        // the word finder should locate the name (it occurs twice, both on page 1)
        List<RectangleOnPage> rectangles = redactor.getRedactionRectangles(doc, Collections.singletonList(name));
        assertEquals(2, rectangles.size(), "Should find both occurrences of the name");
        for (RectangleOnPage rect : rectangles) {
            assertEquals(1, rect.getPage());
            assertTrue(rect.getWidth() > 0);
            assertTrue(rect.getHeight() > 0);
        }

        redactor.redact(
                doc,
                Collections.singletonList(name),
                null,
                false
        );

        String textAfter = extractTextFromPDF(doc);

        // the name must no longer be extractable from the document
        assertFalse(textAfter.contains(name), "Name should be removed from the PDF");
        assertFalse(textAfter.contains("Persis"), "First name should be removed from the PDF");
        assertFalse(textAfter.contains("Bentote"), "Last name should be removed from the PDF");

        // but unrelated text must survive the redaction
        assertTrue(textAfter.contains("Insurance Company"), "Unrelated text should remain intact");
        assertTrue(textAfter.contains("Policyholder"), "Unrelated text should remain intact");

        // and the word finder should no longer find the name
        List<RectangleOnPage> rectangles2 = redactor.getRedactionRectangles(doc, Collections.singletonList(name));
        assertTrue(rectangles2.isEmpty(), "No rectangles should be found after redaction");

        doc.close();
    }

    // Box placement: found rectangles must be in native (unrotated) page space, so the
    // expected word centre is identical for every page rotation of the same content.
    @Test
    void testWordBoxPlacementWithPageRotation() throws IOException {
        for (int rotation : new int[]{0, 90, 180, 270}) {
            try (PDDocument doc = createTextPdf(rotation, "The quick brown fox jumps over the CONFIDENTIAL fence.")) {
                // "CONFIDENTIAL" starts ~192.7pt into the string drawn at (200, 400), is ~88pt wide
                assertBoxContains(doc, "CONFIDENTIAL", 436.7f, 403.5f, "page rotation " + rotation);
            }
        }
    }

    // Box placement for OCR-style documents: the text itself is drawn rotated and the
    // page /Rotate matches, so it displays upright
    @Test
    void testWordBoxPlacementWithRotatedText() throws IOException {
        float[][] expectedCenters = {
                {90, 296.5f, 636.7f},
                {180, 63.3f, 396.5f},
                {270, 303.5f, 163.3f},
        };
        for (float[] expected : expectedCenters) {
            int rotation = (int) expected[0];
            try (PDDocument doc = new PDDocument()) {
                PDPage page = new PDPage();
                page.setRotation(rotation);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.setTextMatrix(org.apache.pdfbox.util.Matrix.getRotateInstance(Math.toRadians(rotation), 300, 400));
                    cs.showText("The quick brown fox jumps over the CONFIDENTIAL fence.");
                    cs.endText();
                }
                assertBoxContains(doc, "CONFIDENTIAL", expected[1], expected[2], "text direction " + rotation);
            }
        }
    }

    // Box placement when the cropBox origin is not (0, 0)
    @Test
    void testWordBoxPlacementWithOffsetCropBox() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(100, 50, 612, 792));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(300, 450);
                cs.showText("The quick brown fox jumps over the CONFIDENTIAL fence.");
                cs.endText();
            }
            assertBoxContains(doc, "CONFIDENTIAL", 536.7f, 453.5f, "offset cropBox");
        }
    }

    /** Asserts that the box found for the word contains the expected centre point. */
    private void assertBoxContains(PDDocument doc, String word, float cx, float cy, String label) throws IOException {
        List<RectangleOnPage> rects = redactor.getRedactionRectangles(doc, Collections.singletonList(word));
        assertEquals(1, rects.size(), label + ": should find exactly one box");
        RectangleOnPage r = rects.get(0);
        assertTrue(r.getX() <= cx && cx <= r.getX() + r.getWidth(),
                label + ": centre x " + cx + " not inside box x=[" + r.getX() + ", " + (r.getX() + r.getWidth()) + "]");
        assertTrue(r.getY() <= cy && cy <= r.getY() + r.getHeight(),
                label + ": centre y " + cy + " not inside box y=[" + r.getY() + ", " + (r.getY() + r.getHeight()) + "]");
        // sanity: the box is word-sized, not line- or page-sized (width/height swap for vertical text)
        float longSide = Math.max(r.getWidth(), r.getHeight());
        float shortSide = Math.min(r.getWidth(), r.getHeight());
        assertTrue(longSide < 120, label + ": box unexpectedly large: " + longSide);
        assertTrue(shortSide < 30, label + ": box unexpectedly thick: " + shortSide);
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
                    null,
                    false
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
