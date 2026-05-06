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

package nz.co.rock3125.pdfredactor;

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
    private final List<Pattern> targetPatterns = new ArrayList<>();
    private final List<Rectangle2D> foundBoundingBoxes = new ArrayList<>();

    // constructor
    public WordFinder(List<String> targetWords) {
        super();
        setSortByPosition(true); // Mandatory for rotated pages

        // Pre-compile regex patterns with word boundaries (\b) and case insensitivity
        for (String word : targetWords) {
            if (word != null && !word.trim().isEmpty()) {
                targetPatterns.add(Pattern.compile("\\b" + Pattern.quote(word) + "\\b", Pattern.CASE_INSENSITIVE));
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

                    float x = firstChar.getXDirAdj();
                    float y = firstChar.getPageHeight() - firstChar.getYDirAdj();
                    float width = (lastChar.getXDirAdj() + lastChar.getWidthDirAdj()) - x;
                    float height = firstChar.getHeightDir();

                    foundBoundingBoxes.add(new Rectangle2D.Float(x - 1, y - 2, width + 2, height + 4));
                }
            }
        }
        super.writeString(text, textPositions);
    }

    public List<Rectangle2D> getFoundBoundingBoxes() {
        return foundBoundingBoxes;
    }

}
