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
public class TextMenuItem implements MenuItem {

    private String text;

    public TextMenuItem() {
        text = "";
    }
    
    public TextMenuItem(String text) {
        this.text = text;
    }
    
    public void setText(String text) {
        this.text = text;
    }
    
    public void draw(Canvas g, Paint p, int x, int y) {
        p.setTextAlign(Paint.Align.CENTER);         // Graphics.HCENTER
        g.drawText(text, x, y - p.ascent(), p);     // Graphics.TOP
    }

    public int getWidth(Paint p) {
        return (int) Math.ceil(p.measureText(" " + text + " "));
    }

}
