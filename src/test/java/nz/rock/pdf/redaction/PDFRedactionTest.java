package nz.rock.pdf.redaction;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PDFRedactionTest {

    @Test
    public void testRedaction1() throws IOException {
        // load our test PDF
        byte[] originalPDF = loadBinary("/saratoga-townhouse-updated-inclusions.pdf");
        assertTrue(originalPDF.length > 0);
        checkPDF(originalPDF, 0, Arrays.asList("earthworks"), null);
        checkPDF(originalPDF, 1, Arrays.asList("external"), null);

        // red boxes document
        PDDocument redDocument = Loader.loadPDF(originalPDF);
        // draw the red boxes, leave the text
        PDFRedactor stripper = new PDFRedactor(redDocument, false);
        stripper.addRegion(0, 10f, 10f, 100.0f, 100.0f);
        stripper.setTextRedactionList(Arrays.asList("earthworks", "roofing", "farm", "external"));
        stripper.apply();
        ByteArrayOutputStream redBOS = new ByteArrayOutputStream();
        redDocument.save(redBOS);
        redDocument.close();
        byte[] redBoxPDF = redBOS.toByteArray();

        checkPDF(redBoxPDF, 0, Arrays.asList("earthworks"), null);
        checkPDF(redBoxPDF, 1, Arrays.asList("external"), null);

        // black boxes document
        PDDocument blackDocument = Loader.loadPDF(originalPDF);
        // draw the red boxes, leave the text
        PDFRedactor blackStripper = new PDFRedactor(blackDocument, true);
        blackStripper.addRegion(0, 10.0f, 10.0f, 100.0f, 100.0f);
        blackStripper.setTextRedactionList(Arrays.asList("earthworks", "roofing", "farm", "external"));
        blackStripper.apply();
        ByteArrayOutputStream blackBOS = new ByteArrayOutputStream();
        blackDocument.save(blackBOS);
        blackDocument.close();
        byte[] blackBOXPdf = blackBOS.toByteArray();

        checkPDF(blackBOXPdf, 0, Arrays.asList("rear"),
                Arrays.asList("earthworks", "roofing", "farm", "external"));
        checkPDF(blackBOXPdf, 1, Arrays.asList("externally"),
                Arrays.asList("earthworks", "roofing", "farm"));

//        writeBinary("test1.pdf", redBoxPDF);
//        writeBinary("test2.pdf", blackBOXPdf);
    }


    @Test
    public void testRedaction2() throws IOException {
        // load our test PDF
        byte[] originalPDF = loadBinary("/rotated_90_area.pdf");
        assertTrue(originalPDF.length > 0);
        checkPDF(originalPDF, 0, Arrays.asList("Engineering"), null);

        // black boxes document
        PDDocument blackDocument = Loader.loadPDF(originalPDF);
        // draw the red boxes, leave the text
        PDFRedactor blackStripper = new PDFRedactor(blackDocument, true);
        blackStripper.addRegion(0, 10.0f, 10.0f, 100.0f, 100.0f);
        blackStripper.setTextRedactionList(Arrays.asList("Engineering"));
        blackStripper.apply();
        ByteArrayOutputStream blackBOS = new ByteArrayOutputStream();
        blackDocument.save(blackBOS);
        blackDocument.close();
        byte[] blackBOXPdf = blackBOS.toByteArray();

        checkPDF(blackBOXPdf, 0, null, Arrays.asList("Engineering"));

//        writeBinary("test3.pdf", blackBOXPdf);
    }


    @Test
    public void testRedaction3() throws IOException {
        // load our test PDF
        byte[] originalPDF = loadBinary("/rotated_180_area.pdf");
        assertTrue(originalPDF.length > 0);
        checkPDF(originalPDF, 0, Arrays.asList("richiesta"), null);

        // black boxes document
        PDDocument blackDocument = Loader.loadPDF(originalPDF);
        // draw the red boxes, leave the text
        PDFRedactor blackStripper = new PDFRedactor(blackDocument, true);
        blackStripper.addRegion(0, 10.0f, 10.0f, 100.0f, 100.0f);
        blackStripper.setTextRedactionList(Arrays.asList("richiesta"));
        blackStripper.apply();
        ByteArrayOutputStream blackBOS = new ByteArrayOutputStream();
        blackDocument.save(blackBOS);
        blackDocument.close();
        byte[] blackBOXPdf = blackBOS.toByteArray();

        checkPDF(blackBOXPdf, 0, null, Arrays.asList("richiesta"));

//        writeBinary("test4.pdf", blackBOXPdf);
    }


    @Test
    public void testRedaction4() throws IOException {
        // load our test PDF
        byte[] originalPDF = loadBinary("/rotated_270_area.pdf");
        assertTrue(originalPDF.length > 0);
        checkPDF(originalPDF, 0, Arrays.asList("Zaglio"), null);

        // black boxes document
        PDDocument blackDocument = Loader.loadPDF(originalPDF);
        // draw the red boxes, leave the text
        PDFRedactor blackStripper = new PDFRedactor(blackDocument, true);
        blackStripper.addRegion(0, 10.0f, 10.0f, 100.0f, 100.0f);
        blackStripper.setTextRedactionList(Arrays.asList("Zaglio"));
        blackStripper.apply();
        ByteArrayOutputStream blackBOS = new ByteArrayOutputStream();
        blackDocument.save(blackBOS);
        blackDocument.close();
        byte[] blackBOXPdf = blackBOS.toByteArray();

        checkPDF(blackBOXPdf, 0, null, Arrays.asList("Zaglio"));

//        writeBinary("test5.pdf", blackBOXPdf);
    }

    /////////////////////////////////////////////////////////////////////

    /**
     * unit test helper - check a page of a pdf file contains and does not contain certain text
     * case-insensitive
     */
    private void checkPDF(byte[] pdfBytes, int page, List<String> mustContain, List<String> mustNotContain) throws IOException {
        PDDocument document = Loader.loadPDF(pdfBytes);
        PDFTextGatherer gatherer = new PDFTextGatherer(document);
        gatherer.apply();
        String page0Str = gatherer.getPageText(page).toLowerCase();
        if (mustContain != null) {
            for (String str : mustContain) {
                assertTrue(page0Str.contains(str.toLowerCase()), "expected page " + page + " to contain \"" + str + "\", it did not");
            }
        }
        if (mustNotContain != null) {
            for (String str : mustNotContain) {
                assertFalse(page0Str.contains(str.toLowerCase()), "expected page " + page + " to NOT contain \"" + str + "\", it does though");
            }
        }
    }


    /**
     * load a binary from resources
     *
     * @param resourceFilename the name of the file in the resource
     * @return a byte array with the data
     */
    private byte[] loadBinary(String resourceFilename) throws IOException {
        assertNotNull(resourceFilename);
        try (InputStream inputStream = PDFRedactionTest.class.getResourceAsStream(resourceFilename)) {
            if (inputStream == null)
                throw new FileNotFoundException(resourceFilename);
            return inputStream.readAllBytes();
        }
    }

    /**
     * helper - write binary file to disk
     * @param filename the location of the file
     * @param bytes the bytes to write
     */
    private void writeBinary(String filename, byte[] bytes) throws IOException {
        assertNotNull(filename);
        assertNotNull(bytes);
        try (OutputStream outputStream = new FileOutputStream(filename)) {
            outputStream.write(bytes);
        }
    }

}
