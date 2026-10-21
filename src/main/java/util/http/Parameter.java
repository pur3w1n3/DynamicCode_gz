package util.http;

/** 编译期桩：只声明本插件用到的方法。 */
public class Parameter {
    public Parameter add(String name, String value) {
        return this;
    }

    public Parameter add(String name, byte[] value) {
        return this;
    }
}