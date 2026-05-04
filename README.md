# PDFRedactor
PDFRedactor is a Java-based utility designed to physically remove sensitive information from PDF documents
using the Apache PDFBox library. Unlike simple annotation tools that merely place a black box over content,
this engine intercepts the PDF content stream to modify or delete the underlying text and image data.

## Features
* Permanent Text Redaction: Intercepts PDF text operators (Tj, TJ, etc.) to strip sensitive characters while maintaining visual layout.
* Keyword Matching: Automatically identifies and redacts specific phrases across multiple pages.
* Image Pixel Masking: Detects when a raster image overlaps with a redaction zone and re-encodes the image with blacked-out pixels.
* Vector Content Filtering: Drops Form XObjects (vector groups) entirely if they intersect with redaction regions.
* Preview Mode: Support for a "non-destructive" mode to draw red outlines for verification before finalizing redactions.

## Usage
The following example demonstrates how to initialize the redactor, define manual regions, set a keyword list,
and apply the changes.

```java
// Load your PDFBox document
PDDocument document = Loader.loadPDF(new File("input.pdf"));

// 1. Instantiate the redactor
// Parameters: (PDDocument, boolean redact)
// Use 'true' to permanently redact, 'false' for red-outline preview mode
PDFRedactor r = new PDFRedactor();
r.redact(
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
./gradlew jar
# output: ./build/libs/pdfredactor-1.0.0.jar
```

## Requirements
* Java 21+
* Apache PDFBox 3.x.
