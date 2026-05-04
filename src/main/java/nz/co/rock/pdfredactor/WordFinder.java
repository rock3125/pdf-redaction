package nz.co.rock.pdfredactor;

import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Internal utility to find the physical bounding boxes of target words.
 */
class WordFinder extends PDFTextStripper {
    private final List<String> targetWords;
    private final List<Rectangle2D> foundBoundingBoxes = new ArrayList<>();

    public WordFinder(List<String> targetWords) throws IOException {
        super();
        setSortByPosition(true); // Mandatory for rotated pages
        this.targetWords = targetWords;
    }

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
        String lowerText = text.toLowerCase();
        for (String word : targetWords) {
            String lowerWord = word.toLowerCase();
            int index = lowerText.indexOf(lowerWord);

            while (index >= 0) {
                int endIndex = index + word.length() - 1;

                if (index < textPositions.size() && endIndex < textPositions.size()) {
                    TextPosition firstChar = textPositions.get(index);
                    TextPosition lastChar = textPositions.get(endIndex);

                    float x = firstChar.getXDirAdj();
                    float y = firstChar.getPageHeight() - firstChar.getYDirAdj();
                    float width = (lastChar.getXDirAdj() + lastChar.getWidthDirAdj()) - x;
                    float height = firstChar.getHeightDir();

                    foundBoundingBoxes.add(new Rectangle2D.Float(x - 1, y - 2, width + 2, height + 4));
                }

                index = lowerText.indexOf(lowerWord, index + 1);
            }
        }
        super.writeString(text, textPositions);
    }

    public List<Rectangle2D> getFoundBoundingBoxes() {
        return foundBoundingBoxes;
    }
}
