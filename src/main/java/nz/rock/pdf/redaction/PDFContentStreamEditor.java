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
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;

import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;


/**
 * Intercepts the PDF content stream to examine forms, images, and text.
 * It allows for real-time filtering or modification of PDF operations (redaction).
 */
public class PDFContentStreamEditor extends PDFTextStripper {

    private final PDDocument document; // the PDF document we're processing
    private ContentStreamWriter replacement = null; // redaction destination stream (create new PDF)
    private boolean inOperator = false;
    private boolean useReplacement = false;

    public PDFContentStreamEditor(PDDocument document) {
        this.document = document;
    }

    /**
     * Hook for subclasses to perform logic before an operator is written to the new stream.
     */
    protected void nextOperation(Operator operator, List<COSBase> operands) {
        // Placeholder for custom logic in extending classes
    }

    /**
     * Writes the current PDF operator and its operands to the replacement content stream.
     */
    protected void write(ContentStreamWriter contentStreamWriter, Operator operator, List<COSBase> operands) throws IOException {
        if (contentStreamWriter != null) {
            contentStreamWriter.writeTokens(operands);
            contentStreamWriter.writeToken(operator);
        }
    }

    // start processing a new page
    @Override
    public void processPage(PDPage page) throws IOException {
        // Create a new stream that will eventually replace the existing page content
        PDStream stream = new PDStream(document);
        try (OutputStream replacementStream = stream.createOutputStream(COSName.FLATE_DECODE)) {
            replacement = new ContentStreamWriter(replacementStream);

            // Start the standard PDFBox parsing process
            super.processPage(page);

        } finally {
            replacement = null;
        }

        // Overwrite the page's content with our newly filtered stream
        page.setContents(stream);
    }

    @Override
    public void showForm(PDFormXObject form) throws IOException {

        // Capture the current writer (page-level or parent-form-level)
        ContentStreamWriter parentWriter = this.replacement;

        // Use a temporary buffer to collect the redacted operators
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try {
            // Create a temporary writer for this form's content
            this.replacement = new ContentStreamWriter(baos);

            // Process the form's internal instructions
            // This triggers processOperator() for every item inside
            super.showForm(form);

            // Force the writer to flush everything into our buffer
            // Note: ContentStreamWriter doesn't have a close(), but we ensure the buffer is ready
        } finally {
            // Restore the parent writer context immediately
            this.replacement = parentWriter;
        }

        // Now that we are out of the processing loop, overwrite the Form's stream
        if (useReplacement) {
            try (OutputStream os = form.getStream().createOutputStream(COSName.FLATE_DECODE)) {
                os.write(baos.toByteArray());
            }
        }

    }

    /**
     * Determines if a Form XObject (vector group) should be removed based on its location.
     * Fixed matrix concatenation and coordinate mapping.
     */
    private boolean shouldDropForm(PDFormXObject form) {
        if (!(this instanceof PDFRedactor redactor) || !redactor.isRedact()) {
            return false;
        }

        try {
            PDRectangle bbox = form.getBBox();
            if (bbox == null) return false;

            // Start with the Current Transformation Matrix (CTM) from the graphics state
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix().clone();

            // Combine with the Form's internal Matrix.
            // In PDFBox, to get the correct global position, the form's matrix
            // is applied BEFORE the CTM.
            Matrix formMatrix = form.getMatrix();
            if (formMatrix != null) {
                ctm.concatenate(formMatrix);
            }

            // Transform the bounding box using the combined matrix
            // We use the matrix to transform the corners of the local BBox into Page Space
            GeneralPath path = new GeneralPath();
            path.moveTo(bbox.getLowerLeftX(), bbox.getLowerLeftY());
            path.lineTo(bbox.getUpperRightX(), bbox.getLowerLeftY());
            path.lineTo(bbox.getUpperRightX(), bbox.getUpperRightY());
            path.lineTo(bbox.getLowerLeftX(), bbox.getUpperRightY());
            path.closePath();

            // Transform the local coordinates into global page coordinates
            java.awt.Shape globalShape = ctm.createAffineTransform().createTransformedShape(path);
            Rectangle2D globalBounds = globalShape.getBounds2D();
            PDRectangle cropBox = getCurrentPage().getCropBox();
            float pageHeight = cropBox.getHeight();

            // Your regions are stored using a 'flipped' Y (top-down).
            // The globalBounds.getY() currently represents the distance from the BOTTOM.
            // We must convert globalBounds to match your redactor's TOP-DOWN storage.

            double normalizedY = pageHeight - (globalBounds.getY() + globalBounds.getHeight());

            Rectangle2D normalizedBounds = new Rectangle2D.Double(
                    globalBounds.getX() - cropBox.getLowerLeftX(),
                    normalizedY - cropBox.getLowerLeftY(),
                    globalBounds.getWidth(),
                    globalBounds.getHeight()
            );

            // Now check if this normalized area overlaps with user-defined zones
            return redactor.matchesImageRegion(normalizedBounds);

        } catch (Exception ex) {
            // exception - failed - keep the form
            return false;
        }
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {

        // Handle 'Do' operators (Draws an external object like an Image or a Form)
        if ("Do".equals(operator.getName()) && !operands.isEmpty() && operands.get(0) instanceof COSName name) {
            PDResources resources = getResources();
            if (resources != null) {
                PDXObject xobject = resources.getXObject(name);
                if (xobject instanceof PDFormXObject pdForm) {
                    PDRectangle bbox = pdForm.getBBox();

                    // Check if the form is "huge" (roughly the size of the page)
                    boolean isFullPageForm = bbox.getWidth() >= (getCurrentPage().getCropBox().getWidth() * 0.9f) &&
                            bbox.getHeight() >= (getCurrentPage().getCropBox().getHeight() * 0.9f);

                    if (isFullPageForm) {
                        // Step inside and redact the individual, operators (text/images) inside this full-page form
                        useReplacement = false;
                        showForm(pdForm);

                    } else {
                        // If it's a small specific vector group, we can safely drop it
                        useReplacement =  shouldDropForm(pdForm);
                        showForm(pdForm);
                    }

                } else if (xobject instanceof PDImageXObject) {
                    // If it's a raster image, delegate to the redactor to mask the pixel data
                    if (this instanceof PDFRedactor redactor) {
                        redactor.drawImage(name);
                    }
                }

            }
        }

        // --- Stream Reconstruction Logic ---

        // This flag prevents infinite recursion if super.processOperator calls back into this method
        if (inOperator) {
            super.processOperator(operator, operands);

        } else {
            inOperator = true;

            // Execute custom subclass logic
            nextOperation(operator, operands);

            // Allow PDFBox to update its internal state (graphics state, text position, etc.)
            super.processOperator(operator, operands);

            // If we are at the top level (not inside a Form XObject being unpacked),
            // write the current operator to our new page content stream.
            if (replacement != null) {
                write(replacement, operator, operands);
            }

            inOperator = false;
        }
    }

}
