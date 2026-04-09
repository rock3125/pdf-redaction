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
PDFRedactor r = new PDFRedactor(document, true);

// 2. Add manual redaction regions (pageIndex, x, y, width, height)
r.addRegion(0, 100f, 100f, 200.0f, 200.0f);

// 3. Set a list of keywords to automatically redact across the entire PDF
r.setTextRedactionList(Arrays.asList("earthworks", "roofing", "farm", "external"));

// 4. Run the redaction engine
r.apply();

// 5. Save the sanitized result
document.save(new File("output_redacted.pdf"));
document.close();
```

## How It Works

The engine operates in two distinct phases:
1. Analysis Phase

The PDFRedactor utilizes a PDFTextGatherer to map every character's Unicode value and coordinate position on every page. It then matches your keyword list against this map to generate precise bounding boxes for every occurrence.

2. Stream Editing Phase

The engine extends PDFContentStreamEditor to rewrite the page's content stream:
* Text: As the PDF is being rewritten, the editor checks each character against the redaction regions. Matches are omitted from the new stream, and spacing is adjusted to prevent layout shifts.
* Images: When an image (Do operator) is encountered, the engine calculates the intersection between the image's page-coordinates and the redaction regions. It then modifies the raw pixel data of the image before re-embedding it.
* Vector/Forms: It calculates the bounding box of complex vector groups. If a group overlaps a redaction zone, the entire group is dropped to prevent data leakage.

## Requirements
* Java 17+
* Apache PDFBox 3.x.
