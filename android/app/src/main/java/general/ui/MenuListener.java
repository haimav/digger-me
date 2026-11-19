/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package general.ui;

/**
 *
 * @author Haim Avron
 */
public interface MenuListener {
    /* Android port: the JavaME version passed the Displayable under the menu;
       there are no Displayables on Android, so the menu itself is passed. */
    public void onMenuEvent(MenuItem item, MenuEvent event, MenuScreen menu);
}
