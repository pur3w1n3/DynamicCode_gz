package core.ui.component.dialog;

import java.awt.Component;

/** 编译期桩：只声明本插件用到的方法。 */
public class GOptionPane {
    public static final int ERROR_MESSAGE = 0;
    public static final int INFORMATION_MESSAGE = 1;
    public static final int WARNING_MESSAGE = 2;

    public static void showMessageDialog(Component parent, Object message, String title, int type) {
    }
}