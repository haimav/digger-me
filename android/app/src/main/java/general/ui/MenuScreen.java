/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package general.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/**
 * The type of the command and the priority is ignored. I use the "Command" class so that the same object and
 * listener can be used with this class and with JavaME library classes.
 * <br><br>
 * Android port: the JavaME version was a full screen Canvas that showed a still
 * image of the game behind the menu box. Here the menu is a transparent view laid
 * over the game, which waits (paused) behind it. It draws the same box - translucent,
 * the items in one color and the selected item in another - and is driven by the
 * same game actions (see {@link #keyPressed(int)}), and by touch.
 *
 * @author Haim Avron
 */
public class MenuScreen {
    private String title;

    private final MenuScreenCanvas canvas;

    private ViewGroup parent;
    private View coveredMenu;
    private boolean isOpen;

    // Commands
    private final int itemsNumber;
    private final MenuItem[] items;
    private MenuListener menuListener;

    // Colors
    private int menuBackgroundColor;
    private int menuBackgroundOpacity;
    private int itemsColor;
    private int selectionColor;
    private int titleColor;

    /** Game actions, with the values of javax.microedition.lcdui.Canvas. */
    public static final int UP = 1, LEFT = 2, RIGHT = 5, DOWN = 6, FIRE = 8;

    /**
     *
     */
    private class MenuScreenCanvas extends View {
        private int currentSelection;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float firstItemTop;
        private float lineHeight;

        private int touchedItem = -1;
        private boolean touchedOutside = false;

        public MenuScreenCanvas(Context context) {
            super(context);
            currentSelection = 0;
        }

        protected void onDraw(Canvas graphics) {
            // The font: JavaME used the phone's default font; here, the default
            // font in a size that suits the screen.
            float density = getResources().getDisplayMetrics().density;
            float textSize = Math.min(getWidth(), getHeight()) / 13f;
            paint.setTextSize(Math.max(16 * density, Math.min(30 * density, textSize)));
            Paint.FontMetrics font = paint.getFontMetrics();
            float textHeight = font.descent - font.ascent;
            lineHeight = textHeight * 1.4f;     // a little more than font.getHeight(), for fingers

            // Calculate the box area
            int boxWidth = Math.max(title != null ? (int) paint.measureText(" " + title + " ") : 0, Math.min(getWidth(), getHeight()) * 2 / 3);
            for(int i = 0; i < itemsNumber; i++) {
                if (items[i] != null)
                    boxWidth = Math.max(boxWidth, items[i].getWidth(paint));
            }
            float boxHeight = lineHeight * itemsNumber + /* margin */ + 4 * lineHeight / 10;
            if (title != null)
                boxHeight += lineHeight;
            boxWidth = Math.min(boxWidth, getWidth());
            box.set((getWidth() - boxWidth) / 2f, (getHeight() - boxHeight) / 2f,
                    (getWidth() + boxWidth) / 2f, (getHeight() + boxHeight) / 2f);

            // Draw menu box
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(menuBackgroundColor | (menuBackgroundOpacity << 24));
            graphics.drawRect(box, paint);

            // Put actual menu items
            float heightOffset = box.top + 2 * lineHeight / 10;
            float textOffset = (lineHeight - textHeight) / 2;
            if (title != null) {
                paint.setColor(titleColor | 0xff000000);
                paint.setTextAlign(Paint.Align.CENTER);
                graphics.drawText(title, getWidth() / 2f, heightOffset + textOffset - font.ascent, paint);
                heightOffset += lineHeight;
            }
            firstItemTop = heightOffset;
            for(int i = 0; i < itemsNumber; i++) {
                if (items[i] == null)
                    continue;

                if(i == currentSelection)
                    paint.setColor(selectionColor | 0xff000000);
                else
                    paint.setColor(itemsColor | 0xff000000);
                items[i].draw(graphics, paint, getWidth() / 2, (int) (heightOffset + textOffset));
                heightOffset += lineHeight;
            }
        }

        protected void keyPressed(int gameAction) {
            switch (gameAction) {
            case DOWN:
                do {
                    currentSelection = (currentSelection + 1) % itemsNumber;
                } while (items[currentSelection] == null);
                invalidate();
                break;

            case UP:
                do {
                    currentSelection--;
                    if (currentSelection == -1)
                        currentSelection = itemsNumber - 1;
                } while (items[currentSelection] == null);
                invalidate();
                break;

            case FIRE:
                menuListener.onMenuEvent(items[currentSelection], MenuEvent.SELECT_EVENT, MenuScreen.this);
                if (isOpen())
                    invalidate();
                break;
            case RIGHT:
                menuListener.onMenuEvent(items[currentSelection], MenuEvent.RIGHT_EVENT, MenuScreen.this);
                if (isOpen())
                    invalidate();
                break;
            case LEFT:
                menuListener.onMenuEvent(items[currentSelection], MenuEvent.LEFT_EVENT, MenuScreen.this);
                if (isOpen())
                    invalidate();
                break;
            }
        }

        // Android port: a tap on an item selects it, a tap outside the box goes back.
        public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX(), y = event.getY();
            switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchedItem = itemAt(x, y);
                touchedOutside = !box.contains(x, y);
                if (touchedItem >= 0) {
                    currentSelection = touchedItem;
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (touchedItem >= 0) {
                    int item = itemAt(x, y);
                    if (item >= 0 && item != touchedItem) {
                        touchedItem = currentSelection = item;
                        invalidate();
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                if (touchedItem >= 0 && itemAt(x, y) == touchedItem) {
                    performClick();
                    menuListener.onMenuEvent(items[touchedItem], MenuEvent.SELECT_EVENT, MenuScreen.this);
                } else if (touchedOutside && !box.contains(x, y)) {
                    menuListener.onMenuEvent(null, MenuEvent.BACK_EVENT, MenuScreen.this);
                }
                touchedItem = -1;
                touchedOutside = false;
                if (isOpen())
                    invalidate();
                break;
            case MotionEvent.ACTION_CANCEL:
                touchedItem = -1;
                touchedOutside = false;
                break;
            }
            return true;    // the game behind the menu gets no touches
        }

        public boolean performClick() {
            return super.performClick();
        }

        private int itemAt(float x, float y) {
            if (!box.contains(x, y))
                return -1;
            float top = firstItemTop;
            for (int i = 0; i < itemsNumber; i++) {
                if (items[i] == null)
                    continue;
                if (y >= top && y < top + lineHeight)
                    return i;
                top += lineHeight;
            }
            return -1;
        }

        public void setCurrentSelection(int newSelection) {
            currentSelection = newSelection;
        }
    }

    /**
     *
     * @param context - Android port: the context of the views.
     * @param itemsNumber
     */
    public MenuScreen(Context context, int itemsNumber) {
        this.title = null;
        this.canvas = new MenuScreenCanvas(context);
        setMenuBackgroundColor(0, 0, 0); // Black;
        setItemsColor(255, 255, 255);   // White
        setSelectionColor(255, 0, 0);   // Red
        setTitleColor(0, 0, 255);       // Blue
        this.itemsNumber = itemsNumber;
        this.items = new MenuItem[itemsNumber];
        isOpen = false;
    }


    /**
     * Android port: shows the menu over the views of the given parent (JavaME:
     * open(Display), which made the menu the current Displayable). A menu that
     * was showing makes way for this one, and comes back when it closes.
     *
     * @param parent
     */
    public void open(ViewGroup parent) {
        if (isOpen)
            return;
        isOpen = true;
        canvas.setCurrentSelection(0);
        this.parent = parent;
        coveredMenu = null;
        for (int i = parent.getChildCount() - 1; i >= 0; i--) {
            View child = parent.getChildAt(i);
            if (child instanceof MenuScreenCanvas && child.getVisibility() == View.VISIBLE) {
                coveredMenu = child;
                child.setVisibility(View.INVISIBLE);
                break;
            }
        }
        parent.addView(canvas, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void close() {
        if (isOpen) {
            parent.removeView(canvas);
            if (coveredMenu != null) {
                coveredMenu.setVisibility(View.VISIBLE);
                coveredMenu = null;
            }
            isOpen = false;
        }
    }

    /**
     * Android port: a game action for the menu (the JavaME menu canvas got the
     * keys itself and translated them with getGameAction()).
     */
    public void keyPressed(int gameAction) {
        if (isOpen)
            canvas.keyPressed(gameAction);
    }

    public void setMenuListener(MenuListener menuListener) {
        this.menuListener = menuListener;
    }

    public void setMenuItem(int itemNumber, MenuItem menuItem) {
        if (itemNumber >= itemsNumber)
            throw new IllegalArgumentException("Invalid item number.");
        items[itemNumber] = menuItem;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setItemsColor(int red, int green, int blue) {
        itemsColor = blue + (green << 8) + (red << 16);
    }

    public void setSelectionColor(int red, int green, int blue) {
        selectionColor = blue + (green << 8) + (red << 16);
    }

    public void setTitleColor(int red, int green, int blue) {
        titleColor = blue + (green << 8) + (red << 16);
    }

    public void setMenuBackgroundColor(int red, int green, int blue) {
        setMenuBackgroundColor(red, green, blue, 254);
        menuBackgroundColor = blue + (green << 8) + (red << 16);
    }

    public void setMenuBackgroundColor(int red, int green, int blue, int opacity) {
        menuBackgroundOpacity = opacity;
        menuBackgroundColor = blue + (green << 8) + (red << 16);
    }

    public boolean isOpen() {
        return isOpen;
    }

    /** Android port: shows the changes of the items (e.g. new texts). */
    public void repaint() {
        canvas.invalidate();
    }
}
