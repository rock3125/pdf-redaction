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
implementation 'nz.peter.pdfredaction:pdf-redaction:1.0.0'
```

```xml
<dependency>
    <groupId>nz.peter.pdfredaction</groupId>
    <artifactId>pdf-redaction</artifactId>
    <version>1.0.0</version>
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
        Collections.singletonList(new RectangleOnPage(1, 10, 10, 100, 100))
        );

// Save the modified document into a new PDF with all its metadata removed
document.save(new File("output_redacted.pdf"));
document.close();
```

## Build the JAR
```
gradle jar
# output: ./build/libs/pdf-redaction-1.0.0.jar
```

## Requirements
* Java 17+
* Apache PDFBox 3.x.
