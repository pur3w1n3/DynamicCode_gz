package core.imp;

import core.shell.ShellEntity;
import util.http.ReqParameter;

/** 编译期桩：只声明本插件用到的方法。 */
public interface Payload {
    void init(ShellEntity shellEntity);
    boolean include(String name, byte[] data);
    byte[] evalFunc(String className, String funcName, ReqParameter parameter);
}