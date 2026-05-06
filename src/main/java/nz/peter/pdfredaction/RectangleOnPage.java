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

/**
 * Represents a specific area on a specific page to be redacted.
 */
public class RectangleOnPage {
    private final int page; // the pdf page this rectangle is on
    private final float x; // the coordinates of the bottom-left corner of the rectangle
    private final float y;
    private final float width;
    private final float height;

    /**
     * @param page   1-based page number.
     * @param x      X coordinate (bottom-left origin).
     * @param y      Y coordinate (bottom-left origin).
     * @param width  Width of the rectangle.
     * @param height Height of the rectangle.
     */
    public RectangleOnPage(int page, float x, float y, float width, float height) {
        this.page = page;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public int getPage() { return page; }
    public float getX() { return x; }
    public float getY() { return y; }
    public float getWidth() { return width; }
    public float getHeight() { return height; }
}
