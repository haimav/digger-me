/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package general.ui;

import android.graphics.Canvas;
import android.graphics.Paint;

/**
 *
 * @author Haim Avron
 */
public interface MenuItem {
    
    /**
     * The coordinates are in the middle of where the item should be drawn
     * (Android port: x is the horizontal center and y the top, as with
     * Graphics.HCENTER | Graphics.TOP; the Paint holds the font and the color
     * that the JavaME Graphics used to hold).
     * @param g
     * @param p
     * @param x
     * @param y
     */
    public void draw(Canvas g, Paint p, int x, int y);
    
    /**
     * 
     * @param p
     * @return The width that will be used to draw the item, including buffers to the right and left (to make
     *  it look good).
     */
    public int getWidth(Paint p);

}
