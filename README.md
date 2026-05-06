# PDF-Redaction
PDF-Redaction is a Java-based library designed to physically remove sensitive information from PDF documents
using the Apache PDFBox library. Unlike simple annotation tools that merely place a black box over content,
this engine intercepts the PDF content stream to modify or delete the underlying text and image data, and
removes all metadata from the PDF document.

## Features
* Permanent Text Redaction: Intercepts PDF text operators (Tj, TJ, etc.) to strip sensitive characters while maintaining visual layout.
* Keyword Matching: Automatically identifies and redacts specific phrases across multiple pages.
* Image Pixel Masking: Detects when a raster image overlaps with a redaction zone and re-encodes the image with blacked-out pixels.

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
// Load your PDFBox document
PDDocument document = Loader.loadPDF(new File("input.pdf"));

// 1. Instantiate the redactor
// Parameters: (PDDocument, boolean redact)
// Use 'true' to permanently redact, 'false' for red-outline preview mode
PdfRedaction redaction = new PdfRedaction();
redaction.redact(
        document, 
        Arrays.asList("confidential", "SECRET_WORD"), 
        Collections.singletonList(new RectangleOnPage(1, 10, 10, 100, 100))
        );

// 5. Save the sanitized result
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
