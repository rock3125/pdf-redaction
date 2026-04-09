package nz.rock.pdf.redaction;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * helper for unit test.  Count the number of black pixels inside all the images
 * of a PDF - so we can check if the PDF's images have been redacted
 */
public class ImagePixelAnalyzer {

    // Keep track of processed images to avoid double-counting shared resources
    private final Set<String> processedImages = new HashSet<>();
    private long totalBlackPixels = 0;

    public long countAllBlackPixels(PDDocument document) throws IOException {
        totalBlackPixels = 0;
        processedImages.clear();

        for (PDPage page : document.getPages()) {
            processResources(page.getResources());
        }
        return totalBlackPixels;
    }

    private void processResources(PDResources resources) throws IOException {
        if (resources == null) return;

        for (COSName name : resources.getXObjectNames()) {
            PDXObject xobject = resources.getXObject(name);

            if (xobject instanceof PDImageXObject image) {
                // Unique check using the internal object hash/ID
                String imageId = image.getCOSObject().toString();
                if (!processedImages.contains(imageId)) {
                    countPixelsInImage(image);
                    processedImages.add(imageId);
                }
            } else if (xobject instanceof PDFormXObject form) {
                // Recursively look inside forms for nested images
                processResources(form.getResources());
            }
        }
    }

    private void countPixelsInImage(PDImageXObject pdImage) throws IOException {
        // Convert to a standard RGB bitmap to handle different color spaces consistently
        BufferedImage bitmap = pdImage.getImage();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = bitmap.getRGB(x, y);

                // Mask out alpha and check if R, G, and B are all 0
                // (rgb & 0x00FFFFFF) == 0 is true only for pure black
                if ((rgb & 0x00FFFFFF) == 0) {
                    totalBlackPixels++;
                }
            }
        }
    }

}
