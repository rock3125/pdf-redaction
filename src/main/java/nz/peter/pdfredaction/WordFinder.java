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

import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Internal utility to find the physical bounding boxes of target words.
 */
class WordFinder extends PDFTextStripper {
    private final PDPage page;
    private final List<Pattern> targetPatterns = new ArrayList<>();
    private final List<Rectangle2D> foundBoundingBoxes = new ArrayList<>();

    // constructor
    public WordFinder(PDPage page, List<String> targetWords) {
        super();
        setSortByPosition(true); // Mandatory for rotated pages
        this.page = page;

        // Pre-compile regex patterns with word boundaries (\b) and case insensitivity.
        // UNICODE_CHARACTER_CLASS makes \w cover all Unicode letters (Java's \w is
        // ASCII-only by default, so e.g. macrons would read as word boundaries); it
        // also enables UNICODE_CASE so non-ASCII letters case-fold correctly.
        for (String word : targetWords) {
            if (word != null && !word.trim().isEmpty()) {
                targetPatterns.add(Pattern.compile("(?<!\\w)" + Pattern.quote(word) + "(?!\\w)",
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS));
            }
        }
    }


    /**
     * Overrides the base implementation to process text and identify bounding boxes for specific patterns.
     * Scans the provided text for target patterns and calculates the bounding boxes corresponding
     * to the matched portions on the page.
     *
     * @param text the text string being written, potentially containing target patterns
     * @param textPositions the list of {@link TextPosition} objects corresponding to the characters
     *                      in the text, used for determining the coordinates and dimensions
     *                      of the matches
     * @throws IOException if an error occurs during processing
     */
    @Override
    protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
        if (text == null || textPositions == null) return;

        for (Pattern pattern : targetPatterns) {
            Matcher matcher = pattern.matcher(text);

            while (matcher.find()) {
                int index = matcher.start();
                int endIndex = matcher.end() - 1; // Inclusive index of the last character

                if (index < textPositions.size() && endIndex < textPositions.size()) {
                    TextPosition firstChar = textPositions.get(index);
                    TextPosition lastChar = textPositions.get(endIndex);

                    // The *DirAdj coordinates live in the frame in which the TEXT reads
                    // left-to-right (its writing direction), not the page /Rotate frame.
                    int dir = Math.round(firstChar.getDir());
                    dir = ((dir % 360) + 360) % 360;
                    if (dir != 90 && dir != 180 && dir != 270) dir = 0;

                    float pageW = firstChar.getPageWidth();
                    float pageH = firstChar.getPageHeight();
                    // Height of the direction frame: page width and height swap for 90/270
                    float dirFrameHeight = (dir == 90 || dir == 270) ? pageW : pageH;

                    float vX = firstChar.getXDirAdj();
                    float vY = dirFrameHeight - firstChar.getYDirAdj();
                    float vW = (lastChar.getXDirAdj() + lastChar.getWidthDirAdj()) - vX;
                    float vH = firstChar.getHeightDir();

                    // Add padding to the visual box before transformation so it scales correctly
                    vX -= 1;
                    vY -= 2;
                    vW += 2;
                    vH += 4;

                    // 2. Convert from the direction frame back to native (unrotated) page space
                    float nX = vX;
                    float nY = vY;
                    float nW = vW;
                    float nH = vH;

                    if (dir == 90) {
                        nX = pageW - vY - vH;
                        nY = vX;
                        nW = vH;
                        nH = vW;
                    } else if (dir == 180) {
                        nX = pageW - vX - vW;
                        nY = pageH - vY - vH;
                    } else if (dir == 270) {
                        nX = vY;
                        nY = pageH - vX - vW;
                        nW = vH;
                        nH = vW;
                    }

                    // 3. TextPosition coordinates are relative to the cropBox origin; drawing
                    // happens in absolute user space, so add the origin back
                    PDRectangle cropBox = page.getCropBox();
                    nX += cropBox.getLowerLeftX();
                    nY += cropBox.getLowerLeftY();

                    foundBoundingBoxes.add(new Rectangle2D.Float(nX, nY, nW, nH));
                }
            }
        }
        super.writeString(text, textPositions);
    }

    public List<Rectangle2D> getFoundBoundingBoxes() {
        return foundBoundingBoxes;
    }

}
