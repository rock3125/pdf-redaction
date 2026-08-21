# PDF-Redaction / PDF-Redactor
PDF-Redaction/Redactor is a Java-based library designed to physically remove sensitive information, including PII, from PDF documents building upon the Apache PDFBox library for processing PDFs. Unlike simple annotation tools that merely place a black box over content, this engine intercepts the PDF content stream to modify or delete the underlying text and mask image data. As well as removing all metadata from a PDF document.

PDF-redaction/PDF-redactor is completely free.  Apache 2 licensed, completely open-source for you to use as you like.  PDF-redaction/redactor does not require any online services, it is a pure Java library.

## Features
* Permanent Text Redaction: Intercepts PDF text operators (Tj, TJ, etc.) to strip sensitive characters while maintaining visual layout.
* Keyword Matching: Automatically identifies and redacts specific phrases across multiple pages.
* Image Pixel Masking: Detects when a raster image overlaps with a redaction zone and re-encodes the image with blacked-out pixels.
* Rotation aware.

## Include

```gradle
implementation 'nz.peter.pdfredaction:pdf-redaction:1.0.8'
```

```xml
<dependency>
    <groupId>nz.peter.pdfredaction</groupId>
    <artifactId>pdf-redaction</artifactId>
    <version>1.0.8</version>
</dependency>
```

## Code Usage
The following example demonstrates how to initialize the redactor, define manual regions, set a keyword list,
and apply the changes.

```java
// Load your PDF using PDF Box
PDDocument document = Loader.loadPDF(new File("input.pdf"));

// Instantiate the redactor
PdfRedaction redaction = new PdfRedaction();
// Parameters: (PDDocument, listOf("words to redact"), listOf(PageRectanges()))
redaction.redact(
        document, 
        // look for and remove these words on all pages
        Arrays.asList("confidential", "SECRET_WORD"),
        // redact images @ x=10,y=10,w=100,h=100 on page 1 (the first page)
        Collections.singletonList(new RectangleOnPage(1, 10, 10, 100, 100)),
        // redact if false, and draw a purple rectangle around the text if true
        false
        );

// Save the modified document into a new PDF with all its metadata removed
document.save(new File("output_redacted.pdf"));
document.close();
```

## Build the JAR
```
gradle jar
# output: ./build/libs/pdf-redaction-1.0.8.jar
```

## publish to maven central

```bash
nano ~/.gradle/gradle.properties
./gradlew publishAndReleaseToMavenCentral --no-configuration-cache
```

## Requirements
* Java 17+
* Apache PDFBox 3.x.

## versions
* 1.0.8 annotations are now redacted: appearance streams are scrubbed and painted black, /Contents, rich-text and form-field values (/V, /DV) are cleaned, and matched annotations are boxed
* 1.0.7 text and images inside Form XObjects are now redacted (scrubber descends into forms, shared forms handled document-wide with boxes on every page)
* 1.0.6 fixed redaction of letter-spaced text (Word documents emitting large character spacing compensated by TJ kerning) being missed by the scrubber
* 1.0.5 word matching now treats all Unicode letters as word characters (macrons no longer read as word boundaries) and case-folds non-ASCII letters
* 1.0.4 fixed surviving text shifting out of position when redacting words mid-line: removed glyphs are now replaced by kerning adjustments of equal width
* 1.0.3 fixed whole-line over-redaction on subset fonts, word-boundary scrubbing, box placement for rotated text and offset cropBoxes, and scrubbing of words split across positioning operators
* 1.0.2 added purple outline rects and fixed some issues with rotation
* 1.0.1 fixed word boundaries for more complex entities such as telephone numbers
* 1.0.0 initial release
