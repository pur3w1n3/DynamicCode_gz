package core.imp;

import core.shell.ShellEntity;
import javax.swing.JPanel;

/** 编译期桩：运行期由宿主提供。 */
public interface Plugin {
    void init(ShellEntity shellEntity);
    JPanel getView();
}