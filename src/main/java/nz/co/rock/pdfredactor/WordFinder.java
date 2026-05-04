package nz.co.rock.pdfredactor;

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

    public WordFinder(List<String> targetWords) throws IOException {
        super();
        setSortByPosition(true); // Mandatory for rotated pages

        // Pre-compile regex patterns with word boundaries (\b) and case insensitivity
        for (String word : targetWords) {
            if (word != null && !word.trim().isEmpty()) {
                targetPatterns.add(Pattern.compile("\\b" + Pattern.quote(word) + "\\b", Pattern.CASE_INSENSITIVE));
            }
        }
    }

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
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
