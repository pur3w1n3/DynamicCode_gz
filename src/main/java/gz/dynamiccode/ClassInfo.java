package gz.dynamiccode;

import java.nio.charset.Charset;

/** class 文件元信息：魔数、常量池类名、字节码版本。 */
public final class ClassInfo {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final String className;
    private final int majorVersion;

    private ClassInfo(String className, int majorVersion) {
        this.className = className;
        this.majorVersion = majorVersion;
    }

    public String getClassName() {
        return className;
    }

    public int getMajorVersion() {
        return majorVersion;
    }

    /** 解析失败返回 null。 */
    public static ClassInfo parse(byte[] b) {
        if (!isClassFile(b)) {
            return null;
        }
        String name = parseClassName(b);
        if (name == null || name.length() == 0) {
            return null;
        }
        return new ClassInfo(name, ((b[6] & 0xFF) << 8) | (b[7] & 0xFF));
    }

    public static boolean isClassFile(byte[] b) {
        return b != null && b.length >= 10
                && (b[0] & 0xFF) == 0xCA && (b[1] & 0xFF) == 0xFE
                && (b[2] & 0xFF) == 0xBA && (b[3] & 0xFF) == 0xBE;
    }

    public static String javaName(int major) {
        if (major <= 0) {
            return "unknown";
        }
        switch (major) {
            case 45: return "Java 1.1";
            case 46: return "Java 1.2";
            case 47: return "Java 1.3";
            case 48: return "Java 1.4";
            case 49: return "Java 5";
            case 50: return "Java 6";
            case 51: return "Java 7";
            case 52: return "Java 8";
            default: return "Java " + (major - 44);
        }
    }

    private static String parseClassName(byte[] b) {
        try {
            int cpCount = u2(b, 8);
            if (cpCount <= 1) {
                return null;
            }
            String[] utf8 = new String[cpCount];
            int[] classIndex = new int[cpCount];
            int p = 10;
            for (int i = 1; i < cpCount; i++) {
                int tag = b[p++] & 0xFF;
                switch (tag) {
                    case 1: {
                        int len = u2(b, p);
                        p += 2;
                        if (p + len > b.length) {
                            return null;
                        }
                        utf8[i] = new String(b, p, len, UTF8);
                        p += len;
                        break;
                    }
                    case 3:
                    case 4:
                        p += 4;
                        break;
                    case 5:
                    case 6:
                        p += 8;
                        i++;
                        break;
                    case 7:
                        classIndex[i] = u2(b, p);
                        p += 2;
                        break;
                    case 8:
                    case 16:
                    case 19:
                    case 20:
                        p += 2;
                        break;
                    case 9:
                    case 10:
                    case 11:
                    case 12:
                    case 17:
                    case 18:
                        p += 4;
                        break;
                    case 15:
                        p += 3;
                        break;
                    default:
                        return null;
                }
                if (p > b.length) {
                    return null;
                }
            }
            p += 2;
            int thisClass = u2(b, p);
            if (thisClass <= 0 || thisClass >= cpCount) {
                return null;
            }
            int nameIndex = classIndex[thisClass];
            if (nameIndex <= 0 || nameIndex >= cpCount) {
                return null;
            }
            String internal = utf8[nameIndex];
            return internal == null ? null : internal.replace('/', '.');
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int u2(byte[] b, int offset) {
        return ((b[offset] & 0xFF) << 8) | (b[offset + 1] & 0xFF);
    }
}
