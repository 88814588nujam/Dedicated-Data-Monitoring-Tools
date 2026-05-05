package io.github.whitemagic2014.tts;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;

import io.github.whitemagic2014.tts.bean.Voice;

public class JinshujuTTS {
    public static int TIMEOUT = 8 * 1000;
    public static int LOADINGFREQ = 60 * 1000;

    public static String watchUrl = "https://jinshuju.net/forms/NjaRQx/entries?filter=%7B\"scopeConditions\"%3A%5B%7B\"trigger\"%3A\"field_6\"%2C\"operator\"%3A\"Between\"%2C\"value\"%3A\"today\"%2C\"groupIndex\"%3A1%7D%5D%7D";
    private static String myUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36 Edg/146.0.0.0";
    private static final String CONFIG_FILE = "config/config.properties";

    public static volatile Map<String, Map<String, String>> latestOrders = new LinkedHashMap<>();

    // ================= 全局状态管理 =================
    public static class AppState {
        public static boolean isVoiceEnabled = true;
        public static boolean isBannerEnabled = true;
        public static int verbosity = 0;
        public static boolean notifyNewOrder = true;
        public static boolean notifyCancel = true;
        public static boolean notifyRemove = true;
        public static String widgetPosition = "TR";
        public static String[] channels = {};

        public static void load() {
            isVoiceEnabled = Boolean.parseBoolean(ConfigManager.get("voiceEnabled", "true"));
            isBannerEnabled = Boolean.parseBoolean(ConfigManager.get("bannerEnabled", "true"));
            verbosity = Integer.parseInt(ConfigManager.get("verbosity", "0"));
            notifyNewOrder = Boolean.parseBoolean(ConfigManager.get("notifyNew", "true"));
            notifyCancel = Boolean.parseBoolean(ConfigManager.get("notifyCancel", "true"));
            notifyRemove = Boolean.parseBoolean(ConfigManager.get("notifyRemove", "true"));
            widgetPosition = ConfigManager.get("widgetPosition", "TR");
            channels = ConfigManager.get("channels", "吕勇志--357观光专用").split(",");
        }
    }

    public static void main(String[] args) {
        initSystemTray();
        ConfigManager.init();
        AppState.load();
        startMonitoring();
        // & "D:\apache-maven\apache-maven-3.9.14\bin\mvn.cmd" clean package
        // "-Dmaven.javadoc.skip=true" "-Dgpg.skip" "-DskipTests"
    }

    // ================= 初始化系统托盘 =================
    private static void initSystemTray() {
        if (!SystemTray.isSupported())
            return;
        SystemTray tray = SystemTray.getSystemTray();
        Image trayImage = null;
        try {
            File iconFile = new File("images/icon.png");
            if (iconFile.exists())
                trayImage = ImageIO.read(iconFile);
        } catch (Exception ignored) {
        }

        if (trayImage == null) {
            BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(65, 140, 200));
            g.fillOval(2, 2, 12, 12);
            g.dispose();
            trayImage = img;
        }

        PopupMenu popup = new PopupMenu();
        MenuItem exitItem = new MenuItem("退出监控");
        exitItem.addActionListener(e -> System.exit(0));
        popup.add(exitItem);

        TrayIcon trayIcon = new TrayIcon(trayImage, "357金数据监控系统", popup);
        trayIcon.setImageAutoSize(true);
        try {
            tray.add(trayIcon);
        } catch (Exception ignored) {
        }
    }

    // ================= 核心监控逻辑 =================
    private static void startMonitoring() {
        LoadingUI.show("正在启动357金数据监控系统并验证环境...");
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(4000, 2000)
                    .setUserAgent(ConfigManager.get("userAgent", myUserAgent)));
            injectCookiesToContext(context, ConfigManager.get("cookie", ""));

            Page page = context.newPage();
            Map<String, Map<String, String>> previousOrders = new LinkedHashMap<>();
            boolean isFirstRun = true;

            while (true) {
                boolean isDataValid = false;
                try {
                    // 开始拉取最新数据...
                    if (isFirstRun)
                        LoadingUI.show("正在校验登录状态与尝试抓取数据...");

                    String targetUrl = ConfigManager.get("watchUrl", watchUrl);
                    page.navigate(targetUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.COMMIT));
                    page.waitForTimeout(TIMEOUT);

                    if (page.url().contains("signin")) {
                        // 警告：检测到登录页面，Cookie已失效。
                        LoadingUI.hide();

                        final String[] newCookieStr = new String[1];
                        SwingUtilities.invokeAndWait(() -> newCookieStr[0] = CookieDialog.showDialog());

                        if (newCookieStr[0] != null && !newCookieStr[0].trim().isEmpty()) {
                            ConfigManager.set("cookie", newCookieStr[0]);
                            context.clearCookies();
                            injectCookiesToContext(context, newCookieStr[0]);
                            LoadingUI.show("正在应用新凭证并重新连接...");
                            continue;
                        } else {
                            System.exit(0);
                        }
                    }

                    if (isFirstRun)
                        ControlWidget.showWidget();

                    // ==========================================
                    // 【核心修改】：边滚边抓，破解 ag-Grid 的 DOM 虚拟化
                    // ==========================================
                    Map<String, Map<String, String>> currentOrders = new LinkedHashMap<>();
                    Document finalDoc = null;
                    int expectedCount = -1;

                    int maxScrollTimes = 50; // 足够大的滚动次数
                    int stuckCounter = 0;

                    for (int scrollAttempt = 1; scrollAttempt <= maxScrollTimes; scrollAttempt++) {
                        // 1. 解析当前可见的 DOM 树
                        Document doc = Jsoup.parse(page.content());
                        finalDoc = doc;
                        Element container = doc.selectFirst("div.ag-center-cols-container");

                        // 2. 提取当前屏幕可见的数据并累加到 currentOrders
                        if (container != null) {
                            Elements rows = container.select("div[role='row']");
                            for (int i = rows.size() - 1; i >= 0; i--) {
                                Element row = rows.get(i);
                                Map<String, String> rowData = new HashMap<>();
                                Elements cells = row.select("div[role='gridcell']");
                                for (Element cell : cells) {
                                    String colId = cell.attr("col-id");
                                    String value = cleanEmoji(cell.text().trim());
                                    if (colId != null && !colId.isEmpty())
                                        rowData.put(colId, value);
                                }
                                String orderId = rowData.get("field_4");
                                if (orderId != null && !orderId.isEmpty())
                                    currentOrders.put(orderId, rowData);
                            }
                        }

                        // 3. 提取 expectedCount (仅做记录，不再用于中断循环)
                        Element loadedSpan = doc.selectFirst("div.ag-status-bar-left span:contains(已加载)");
                        if (loadedSpan != null) {
                            String loadedText = loadedSpan.text();
                            String numStr = loadedText.replaceAll("[^0-9]", "");
                            if (!numStr.isEmpty()) {
                                expectedCount = Integer.parseInt(numStr);
                            }
                        }

                        // 4. 执行 JS 页面向下滚动
                        Boolean isScrolled = (Boolean) page.evaluate(
                                "() => {" +
                                        "   var viewport = document.querySelector('.ag-body-viewport');" +
                                        "   if(viewport) {" +
                                        "       var oldScrollTop = viewport.scrollTop;" +
                                        "       viewport.scrollTop += 500;" + // 小步长滚动
                                        "       return viewport.scrollTop > oldScrollTop;" + // 判断是否还能继续往下滚
                                        "   } else {" +
                                        "       return false;" +
                                        "   }" +
                                        "}");

                        // 5. 靠物理滚动到底部来判断是否结束
                        if (isScrolled != null && isScrolled) {
                            page.waitForTimeout(500); // 成功滚动，等待新数据渲染
                            stuckCounter = 0; // 只要能滚，计数器就清零
                        } else {
                            page.waitForTimeout(500);
                            stuckCounter++;
                            // 【核心结束条件】：连续 3 次 滚不动，说明真的到底了，或者网络请求彻底结束了
                            if (stuckCounter >= 3) {
                                break;
                            }
                        }
                    }

                    // ==========================================
                    // 滚动彻底结束后，再进行最终的数据校验
                    // ==========================================
                    int diff = Math.abs(currentOrders.size() - expectedCount);
                    if (expectedCount >= 0 && currentOrders.size() >= expectedCount && diff <= 150) {
                        isDataValid = true;
                        latestOrders = currentOrders; // 更新全局变量
                        ControlWidget.updateDataCount(currentOrders.size());
                    }

                    if (finalDoc != null) {
                        saveHtmlToFile(finalDoc);
                    }
                    // ... 后续的新单提醒逻辑保持不变 ...

                    if (isDataValid) {
                        if (isFirstRun) {
                            // 【核心修改】：防“假0”机制拦截
                            if (!currentOrders.isEmpty()) {
                                // 抓到了大于0的真实数据，正式建立基准
                                previousOrders = currentOrders;
                                isFirstRun = false;
                            }
                        } else {
                            // 检查新订单
                            if (AppState.notifyNewOrder) {
                                for (Map.Entry<String, Map<String, String>> entry : currentOrders.entrySet()) {
                                    String orderId = entry.getKey();
                                    if (!previousOrders.containsKey(orderId)) {
                                        Map<String, String> data = entry.getValue();
                                        String daily = data.getOrDefault("field_6", "1999-12-31");
                                        if (daily.equals(getDaily())) {
                                            String channel = data.getOrDefault("field_20", "吕勇志--357观光专用");
                                            boolean usefulOrder = isExist(AppState.channels, channel);
                                            if (usefulOrder) {
                                                String orderType = formatOrderType(
                                                        data.getOrDefault("field_19", "点对点"));
                                                String time = data.getOrDefault("field_7", "未知时间");
                                                String from = data.getOrDefault("field_36", "未知起点");
                                                String to = data.getOrDefault("field_37", "未知终点");
                                                String req = data.getOrDefault("field_27", "无");

                                                String msg = "";
                                                if (AppState.verbosity == 0)
                                                    msg = "<html>您有新的<font color='#FF6B6B'><b>" + orderType
                                                            + "</b></font>订单！<font color='#FF6B6B'><b>" + time
                                                            + "</b></font>，从"
                                                            + from + "出发去往" + to + "，要求：<font color='#FF6B6B'><b>"
                                                            + req + "</b></font>。订单号：" + orderId + "</html>";
                                                else if (AppState.verbosity == 1) {
                                                    if (orderType.equals("送机"))
                                                        msg = "<html>新的<font color='#FF6B6B'><b>" + orderType
                                                                + "</b></font>订单！<font color='#FF6B6B'><b>" + time
                                                                + "</b></font>，去往" + to + "。</html>";
                                                    else if (orderType.equals("接机"))
                                                        msg = "<html>新的<font color='#FF6B6B'><b>" + orderType
                                                                + "</b></font>订单！<font color='#FF6B6B'><b>" + time
                                                                + "</b></font>，从" + from + "接机。</html>";
                                                    else
                                                        msg = "<html>新的<font color='#FF6B6B'><b>" + orderType
                                                                + "</b></font>订单！<font color='#FF6B6B'><b>" + time
                                                                + "</b></font>。</html>";
                                                } else
                                                    msg = "<html>新的<font color='#FF6B6B'><b>" + orderType
                                                            + "</b></font>订单！<font color='#FF6B6B'><b>" + time
                                                            + "</b></font>。</html>";

                                                triggerNotification(msg);
                                            }
                                        }
                                    }
                                }
                            }
                            // 检查被取消分配
                            if (AppState.notifyCancel) {
                                for (Map.Entry<String, Map<String, String>> entry : currentOrders.entrySet()) {
                                    String orderId = entry.getKey();
                                    Map<String, String> currData = entry.getValue();
                                    Map<String, String> prevData = previousOrders.get(orderId);
                                    if (prevData != null) {
                                        String daily = currData.getOrDefault("field_6", "1999-12-31");
                                        if (daily.equals(getDaily())) {
                                            String prevDriver = prevData.get("field_43");
                                            String currDriver = currData.get("field_43");
                                            if ((prevDriver != null && !prevDriver.isEmpty())
                                                    && (currDriver == null || currDriver.isEmpty())) {
                                                String channel = prevData.getOrDefault("field_20", "吕勇志--357观光专用");
                                                boolean usefulOrder = isExist(AppState.channels, channel);
                                                if (usefulOrder) {
                                                    String orderType = formatOrderType(
                                                            prevData.getOrDefault("field_19", "点对点"));
                                                    String msg = "";
                                                    if (AppState.verbosity == 0)
                                                        msg = "<html>请确认已安排司机的<font color='#FF6B6B'><b>" + orderType
                                                                + "</b></font>订单被取消！原定司机：<font color='#FF6B6B'><b>"
                                                                + prevDriver
                                                                + "</b></font>。订单号："
                                                                + orderId + "</html>";
                                                    else if (AppState.verbosity == 1)
                                                        msg = "<html>订单取消：原司机<font color='#FF6B6B'><b>" + prevDriver
                                                                + "</b></font>，单号" + orderId + "</html>";
                                                    else
                                                        msg = "司机取消" + orderId;

                                                    triggerNotification(msg);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            // 检查订单移除
                            if (AppState.notifyRemove) {
                                for (String oldOrderId : previousOrders.keySet()) {
                                    if (!currentOrders.containsKey(oldOrderId)) {
                                        Map<String, String> oldData = previousOrders.get(oldOrderId);
                                        String daily = oldData.getOrDefault("field_6", "1999-12-31");
                                        if (daily.equals(getDaily())) {
                                            String channel = oldData.getOrDefault("field_20", "吕勇志--357观光专用");
                                            boolean usefulOrder = isExist(AppState.channels, channel);
                                            if (usefulOrder) {
                                                String orderType = formatOrderType(oldData.getOrDefault("field_19", "点对点"));
                                                String time = oldData.getOrDefault("field_7", "未知时间");
                                                String from = oldData.getOrDefault("field_36", "未知起点");
                                                String to = oldData.getOrDefault("field_37", "未知终点");

                                                String msg = "";
                                                if (AppState.verbosity == 0)
                                                    msg = "<html>请注意有订单已被平台侧移除！<font color='#FF6B6B'><b>" + time
                                                            + "的<font color='#FF6B6B'><b>" + orderType + "</b></font>订单，从"
                                                            + from + "到" + to
                                                            + "。订单号：" + oldOrderId + "</html>";
                                                else if (AppState.verbosity == 1)
                                                    msg = "<html><font color='#FF6B6B'><b>" + time
                                                            + "</b></font>的<font color='#FF6B6B'><b>" + orderType
                                                            + "</b></font>订单已被平台侧移除。</html>";
                                                else
                                                    msg = "平台侧移除订单：" + oldOrderId;

                                                triggerNotification(msg);
                                            }
                                        }
                                    }
                                }
                            }
                            previousOrders = currentOrders;
                        }
                    }
                } catch (Exception e) {
                }

                LoadingUI.hide();
                try {
                    long sleepMillis;

                    if (!isDataValid) {
                        // 场景 1：某一次网络延迟、数据异常，直接延时 10 秒重试
                        sleepMillis = 10 * 1000;
                    } else if (isFirstRun) {
                        // 场景 2：初始无加载真实数据（被“假0”拦截），延时 5 秒重试
                        sleepMillis = 5 * 1000;
                    } else {
                        // 场景 3：正常获取到数据！让所有终端严格对齐到系统的下一个【整分钟】
                        long currentMillis = System.currentTimeMillis();

                        // 计算当前时间距离下一个整分（00秒000毫秒）还有多少毫秒
                        sleepMillis = 60000 - (currentMillis % 60000);

                        // 【防抖机制】：如果这次网页加载完，距离整分已经不足 x 秒了（比如 59.5 秒才加载完）
                        // 直接睡到下下个整分，防止一秒钟内发起两次短促请求被平台封禁
                        if (sleepMillis < 10 * 1000) {
                            sleepMillis += 60000;
                        }
                    }

                    Thread.sleep(sleepMillis);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    private static boolean isExist(String[] arr, String target) {
        for (String s : arr) {
            if (s != null && s.equals(target))
                return true;
        }
        return false;
    }

    private static String formatOrderType(String raw) {
        if (raw.contains("送机"))
            return "送机";
        if (raw.contains("接机"))
            return "接机";
        if (raw.contains("包车"))
            return "包车";
        return "点对点";
    }

    private static void triggerNotification(String text) {
        String safeText = cleanEmoji(text);
        if (AppState.isBannerEnabled)
            BannerUI.showBanner(safeText);
        if (AppState.isVoiceEnabled)
            speek(safeText);
    }

    private static void injectCookiesToContext(BrowserContext context, String cookieStr) {
        if (cookieStr == null || cookieStr.trim().isEmpty())
            return;
        for (String pair : cookieStr.split(";")) {
            int index = pair.indexOf("=");
            if (index > 0) {
                try {
                    context.addCookies(
                            Arrays.asList(new Cookie(pair.substring(0, index).trim(), pair.substring(index + 1).trim())
                                    .setDomain(".jinshuju.net").setPath("/")));
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void saveHtmlToFile(Document doc) {
        try {
            Path directoryPath = Paths.get(ConfigManager.get("storagePath", "D:\\IMS357\\TTS\\"));
            Path outputPath = directoryPath.resolve("jinshuju.html");
            if (Files.notExists(directoryPath))
                Files.createDirectories(directoryPath);
            try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
                writer.write(cleanEmoji(doc.html()));
            }
        } catch (Exception ignored) {
        }
    }

    private static String cleanEmoji(String text) {
        if (text == null)
            return "";
        return text.replaceAll(
                "[\\x{1F600}-\\x{1F64F}\\x{1F300}-\\x{1F5FF}\\x{1F680}-\\x{1F6FF}\\x{1F700}-\\x{1F77F}\\x{1F780}-\\x{1F7FF}\\x{1F800}-\\x{1F8FF}\\x{1F900}-\\x{1F9FF}\\x{1FA00}-\\x{1FA6F}\\x{1FA70}-\\x{1FAFF}\\x{2600}-\\x{26FF}\\x{2700}-\\x{27BF}\\x{2300}-\\x{23FF}]",
                "");
    }

    private static String getDaily() {
        LocalDate now = LocalDate.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        String todayStr = now.format(formatter);
        return todayStr;
    }

    private static void speek(String voiceString) {
        // 自动过滤掉 HTML 标签，防止语音把代码读出来
        voiceString = voiceString.replaceAll("<[^>]*>", "");
        String voiceName = "zh-CN-XiaoyiNeural";
        Optional<Voice> vOpt = TTSVoice.provides().stream().filter(v -> voiceName.equals(v.getShortName())).findFirst();
        if (!vOpt.isPresent())
            return;

        String tempDir = System.getProperty("java.io.tmpdir");
        String fileId = "tts_" + UUID.randomUUID().toString().substring(0, 8);
        File file = new File(tempDir, fileId + ".mp3");

        try {
            new TTS(vOpt.get(), voiceString).findHeadHook().isRateLimited(true).storage(tempDir).fileName(fileId)
                    .overwrite(true).formatMp3().trans();
            if (!file.exists())
                return;
            String script = String.format(
                    "Add-Type -AssemblyName presentationCore; $m = New-Object System.Windows.Media.MediaPlayer; $m.Open('%s'); while($m.DownloadProgress -lt 1) { Start-Sleep -Milliseconds 100 }; $m.Play(); Start-Sleep -Milliseconds 500; while($m.Position -lt $m.NaturalDuration.TimeSpan -and $m.HasAudio) { Start-Sleep -Milliseconds 200 }",
                    file.getAbsolutePath().replace("\\", "/"));
            // System.out.println(voiceString);
            new ProcessBuilder("powershell", "-ExecutionPolicy", "Bypass", "-c", script).start().waitFor();
        } catch (Exception ignored) {
        } finally {
            if (file.exists())
                file.delete();
        }
    }

    // ================= 条幅消息 UI (iPhone 气泡风通知引擎) =================
    public static class BannerUI {
        private static final List<NotificationBubble> activeBubbles = new ArrayList<>();
        private static Timer engineTimer;
        private static int MAX_BUBBLES = 3;

        public static void showBanner(String message) {
            SwingUtilities.invokeLater(() -> {
                NotificationBubble bubble = new NotificationBubble(message);
                activeBubbles.add(0, bubble); // 永远插在第一位（最上面）

                // 如果超过3个，将后面多余的全部标记为过期并开始渐隐滑走
                for (int i = 0; i < activeBubbles.size(); i++) {
                    if (i >= MAX_BUBBLES) {
                        activeBubbles.get(i).expire();
                    }
                }

                if (engineTimer == null || !engineTimer.isRunning()) {
                    engineTimer = new Timer(16, e -> animateFrame());
                    engineTimer.start();
                }
            });
        }

        private static void animateFrame() {
            boolean needsUpdate = false;
            Iterator<NotificationBubble> it = activeBubbles.iterator();
            int visibleIndex = 0;

            while (it.hasNext()) {
                NotificationBubble b = it.next();

                // 未过期的气泡分配目标位置：新的在上面，旧的被顶下去
                if (!b.isExpired) {
                    b.targetY = 20 + visibleIndex * 60; // 高度 50 + 间隙 10
                    visibleIndex++;
                }

                b.tick(); // 执行每一帧的物理运算

                if (b.isDead()) {
                    b.window.dispose();
                    it.remove();
                } else {
                    needsUpdate = true;
                }
            }

            if (!needsUpdate && engineTimer != null) {
                engineTimer.stop();
            }
        }

        private static class NotificationBubble {
            JWindow window;
            float currentY = -60; // 初始出生在屏幕外上方
            int targetY = -60;
            float opacity = 0f;
            float targetOpacity = 1f;
            boolean isExpired = false;
            long expireTime;

            NotificationBubble(String message) {
                window = new JWindow();
                window.setAlwaysOnTop(true);
                window.setBackground(new Color(0, 0, 0, 0));

                // 先实例化 Label，用来丈量文字真实的物理长度
                JLabel msgLabel = new JLabel(message, SwingConstants.CENTER);
                msgLabel.setForeground(Color.WHITE);
                msgLabel.setFont(new Font("微软雅黑", Font.BOLD, 14));

                // 给文字左右强制加上 25 像素的内边距（防贴边缓冲垫）
                msgLabel.setBorder(new EmptyBorder(0, 25, 0, 25));

                // 核心修改：动态计算气泡宽度
                int textWidth = msgLabel.getPreferredSize().width;
                int padding = 80; // 左右各留 40 像素的呼吸空间
                int calculatedWidth = textWidth + padding;

                // 设定最小与最大极限宽度边界
                int minWidth = 400;
                int maxWidth = 900; // 如果超过 900 像素，Swing 会自动在结尾加 "..." 缩略
                int finalWidth = Math.max(minWidth, Math.min(calculatedWidth, maxWidth));

                // 将计算后的完美宽度赋予窗体
                window.setSize(finalWidth, 50);

                JPanel panel = new JPanel() {
                    protected void paintComponent(Graphics g) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(new Color(30, 40, 50, 240));
                        g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 25, 25);
                        g2.setColor(new Color(65, 140, 200, 180));
                        g2.drawRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 25, 25);
                        g2.dispose();
                    }
                };
                panel.setOpaque(false);
                panel.setLayout(new BorderLayout());
                panel.add(msgLabel, BorderLayout.CENTER);

                window.setContentPane(panel);

                // 每次都会根据气泡的最终宽度，精确计算让它保持在屏幕绝对水平居中
                Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
                window.setLocation((screen.width - window.getWidth()) / 2, (int) currentY);

                try {
                    window.setOpacity(opacity);
                } catch (Exception ignored) {
                }
                window.setVisible(true);

                // 寿命 8 秒
                expireTime = System.currentTimeMillis() + 8000;
            }

            void expire() {
                if (isExpired)
                    return;
                isExpired = true;
                targetOpacity = 0f;
                targetY = (int) currentY - 20; // 消失时附带轻轻往上飘的退场动画
            }

            void tick() {
                // 判断是否寿终正寝
                if (!isExpired && System.currentTimeMillis() > expireTime) {
                    expire();
                }

                // 弹性物理插值计算
                currentY += (targetY - currentY) * 0.15f;
                opacity += (targetOpacity - opacity) * 0.1f;

                if (opacity < 0.02f)
                    opacity = 0f;
                if (opacity > 1f)
                    opacity = 1f;

                window.setLocation(window.getLocation().x, (int) currentY);
                try {
                    window.setOpacity(opacity);
                } catch (Exception ignored) {
                }
            }

            boolean isDead() {
                return isExpired && opacity <= 0f;
            }
        }
    }

    // ================= 右上角桌面风小工具 =================
    public static class ControlWidget {
        private static JWindow widget;
        private static int loadedDataCount = 0;
        private static JPanel panel;

        private static Timer animTimer;
        // 【优化】略微增加宽度，使得缩放状态下能完美容纳：Logo -> 间距 -> 静音图标 -> 间距 -> 导出同心圆
        private static final int EXPANDED_WIDTH = 720;
        private static final int COLLAPSED_WIDTH = 184;
        private static int currentWidth = EXPANDED_WIDTH;
        private static boolean isExpanded = true;
        private static boolean isHoveringMute = false;
        private static boolean isHoveringExport = false;
        private static boolean isExporting = false;

        private static double swirlAngle = 0;
        private static double cloudOffset = 0;
        private static Timer fxTimer;

        // 新增UI组件和定时器引用
        private static Image ttsOnImg;
        private static Image ttsOffImg;
        private static JToggleButton voiceBtnRef;
        private static PositionPicker pickerRef;
        private static JLabel closeBtnRef;
        private static JPanel clipContainerRef;

        // 自动缩放与淡出控制
        private static Timer idleTimer;
        private static Timer alphaTimer;
        private static long idleTimeMillis = 0;
        private static float currentOpacity = 1.0f;

        public static Rectangle calculateBounds(int width, int height) {
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            int margin = 20;
            int taskbarOffset = 50;
            int x = 0, y = 0;
            switch (AppState.widgetPosition) {
                case "TL":
                    x = margin;
                    y = margin;
                    break;
                case "BR":
                    x = screen.width - width - margin;
                    y = screen.height - height - taskbarOffset;
                    break;
                case "BL":
                    x = margin;
                    y = screen.height - height - taskbarOffset;
                    break;
                case "TR":
                default:
                    x = screen.width - width - margin;
                    y = margin;
                    break;
            }
            return new Rectangle(x, y, width, height);
        }

        public static void updateDataCount(int count) {
            loadedDataCount = count;
            if (panel != null)
                panel.repaint();
        }

        public static void updateLocation() {
            if (widget != null) {
                widget.setBounds(calculateBounds(currentWidth, widget.getHeight()));
            }
        }

        private static void exportNoDriverOrders() {
            Map<String, Map<String, String>> data = latestOrders;
            if (data == null || data.isEmpty()) {
                BannerUI.showBanner("当前暂无数据可供导出");
                return;
            }

            List<Map<String, String>> targetList = new ArrayList<>();
            for (Map<String, String> row : data.values()) {
                String driver = row.get("field_43");
                String type = row.get("field_19");
                boolean noDriver = (driver == null || driver.trim().isEmpty());
                boolean isTransfer = (type != null && (type.contains("接机") || type.contains("送机")));
                if (noDriver && isTransfer) {
                    // 使用 new HashMap 包装一层，防止修改原数据，并方便后续存入预处理字段
                    targetList.add(new HashMap<>(row));
                }
            }

            if (targetList.isEmpty()) {
                BannerUI.showBanner("没有符合条件的未分配接送机订单");
                return;
            }
            targetList.sort(Comparator.comparing(r -> r.getOrDefault("field_7", "")));

            // ==========================================
            // 预处理：提前解析地址和排版，防止重复调用导致卡顿
            // ==========================================
            for (Map<String, String> row : targetList) {
                String time = row.getOrDefault("field_7", "未知时间");

                // 预解析起点
                String from = row.getOrDefault("field_36", "未知起点");
                if (from.contains("成田") || from.toLowerCase().contains("narita"))
                    from = "成田";
                else if (from.contains("羽田") || from.toLowerCase().contains("haneda"))
                    from = "羽田";
                from = from.equals("成田") || from.equals("羽田") ? from : AddressParser.getRegionSmart(from);
                row.put("__parsed_from", from); // 存入字典供后续分段筛选

                // 预解析终点
                String to = row.getOrDefault("field_37", "未知终点");
                if (to.contains("成田") || to.toLowerCase().contains("narita"))
                    to = "成田";
                else if (to.contains("羽田") || to.toLowerCase().contains("haneda"))
                    to = "羽田";
                to = to.equals("成田") || to.equals("羽田") ? to : AddressParser.getRegionSmart(to);
                row.put("__parsed_to", to); // 存入字典供后续分段筛选

                String carType = row.getOrDefault("field_27", "");
                if (carType.contains("豪华7"))
                    carType = "豪华7";
                else if (carType.contains("豪华5"))
                    carType = "豪华5";
                else if (carType.contains("10座"))
                    carType = " 10座";
                else if (carType.contains("宝贝"))
                    carType = "宝贝专车";
                else
                    carType = "";

                String flightNo = row.getOrDefault("field_24", "未留航班号");
                String otherService = row.getOrDefault("field_56", "");
                if (otherService.contains("举牌") && otherService.contains("儿童"))
                    otherService = "儿童座椅举牌";
                else {
                    if (otherService.contains("举牌"))
                        otherService = "举牌";
                    else if (otherService.contains("儿童"))
                        otherService = "儿童座椅";
                    else
                        otherService = "";
                }

                flightNo = from.equals("成田") || from.equals("羽田") ? flightNo : "";

                // 生成这一行最终的输出字符串，并缓存
                String formatted = time + from + to + (flightNo.equals("") ? "" : " " + flightNo)
                        + (otherService.equals("") ? "" : otherService) + (carType.equals("") ? "" : carType) + "\r\n";
                row.put("__formatted_str", formatted);
            }

            StringBuilder sb = new StringBuilder();

            // ==========================================
            // 1. 导出全部接送机订单
            // ==========================================
            sb.append("=== 金数据平台暂未分配司机的接送机订单 ===\r\n");
            for (Map<String, String> row : targetList) {
                sb.append(row.get("__formatted_str"));
            }

            // ==========================================
            // 2. 导出送成田
            // ==========================================
            sb.append("\r\n=== 送成田 ===\r\n");
            for (Map<String, String> row : targetList) {
                String type = row.getOrDefault("field_19", "");
                if (type.contains("送机") && "成田".equals(row.get("__parsed_to"))) {
                    sb.append(row.get("__formatted_str"));
                }
            }

            // ==========================================
            // 3. 导出接成田
            // ==========================================
            sb.append("\r\n=== 接成田 ===\r\n");
            for (Map<String, String> row : targetList) {
                String type = row.getOrDefault("field_19", "");
                if (type.contains("接机") && "成田".equals(row.get("__parsed_from"))) {
                    sb.append(row.get("__formatted_str"));
                }
            }

            // ==========================================
            // 4. 导出送羽田
            // ==========================================
            sb.append("\r\n=== 送羽田 ===\r\n");
            for (Map<String, String> row : targetList) {
                String type = row.getOrDefault("field_19", "");
                if (type.contains("送机") && "羽田".equals(row.get("__parsed_to"))) {
                    sb.append(row.get("__formatted_str"));
                }
            }

            // ==========================================
            // 5. 导出接羽田
            // ==========================================
            sb.append("\r\n=== 接羽田 ===\r\n");
            for (Map<String, String> row : targetList) {
                String type = row.getOrDefault("field_19", "");
                if (type.contains("接机") && "羽田".equals(row.get("__parsed_from"))) {
                    sb.append(row.get("__formatted_str"));
                }
            }

            try {
                String ts = new SimpleDateFormat("yyMMddHHmm").format(new Date());
                String p = ConfigManager.get("storagePath", "D:\\IMS357\\TTS\\");
                Path dir = Paths.get(p);
                if (Files.notExists(dir))
                    Files.createDirectories(dir);
                Path file = dir.resolve("金数据" + ts + ".txt");
                Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));

                BannerUI.showBanner("<html>成功导出金数据" + ts + ".txt (共计 <font color='#FF6B6B'><b>" + targetList.size()
                        + "</b></font> 条记录)</html>");

                try {
                    String os = System.getProperty("os.name").toLowerCase();
                    if (os.contains("win")) {
                        String absolutePath = file.toAbsolutePath().toString();

                        String psScript = "$path = '" + absolutePath + "'; " +
                                "$dir = Split-Path $path; " +
                                "$shell = New-Object -ComObject Shell.Application; " +
                                "$window = $shell.Windows() | Where-Object { $_.Document.Folder.Self.Path -eq $dir } | Select-Object -First 1; "
                                +
                                "if ($window) { " +
                                "    $item = $window.Document.Folder.ParseName((Split-Path $path -Leaf)); " +
                                "    $window.Document.SelectItem($item, 13); " +
                                "    try { " +
                                "        $Win32 = Add-Type -MemberDefinition '[DllImport(\"user32.dll\")] public static extern bool SetForegroundWindow(IntPtr hWnd); [DllImport(\"user32.dll\")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);' -Name 'Win32' -PassThru; "
                                +
                                "        $Win32::ShowWindow($window.HWND, 9); " +
                                "        $Win32::SetForegroundWindow($window.HWND); " +
                                "    } catch {} " +
                                "} else { " +
                                "    explorer.exe /select,\"$path\"; " +
                                "}";

                        String encodedCommand = java.util.Base64.getEncoder()
                                .encodeToString(psScript.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));

                        new ProcessBuilder("powershell", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                                "-EncodedCommand", encodedCommand).start();

                    } else if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().open(dir.toFile());
                    }
                } catch (Exception ex) {
                }
            } catch (Exception ex) {
                BannerUI.showBanner("导出数据失败：" + ex.getMessage());
            }
        }

        public static void showWidget() {
            if (widget != null)
                return;
            SwingUtilities.invokeLater(() -> {
                widget = new JWindow();
                widget.setBounds(calculateBounds(currentWidth, 50));
                widget.setAlwaysOnTop(true);
                widget.setBackground(new Color(0, 0, 0, 0));

                Image leftIcon = null;
                try {
                    File f = new File("images/icon.png");
                    if (f.exists())
                        leftIcon = ImageIO.read(f);
                    File fOn = new File("images/tts-on.png");
                    if (fOn.exists())
                        ttsOnImg = ImageIO.read(fOn);
                    File fOff = new File("images/tts-off.png");
                    if (fOff.exists())
                        ttsOffImg = ImageIO.read(fOff);
                } catch (Exception ignored) {
                }
                final Image finalLeftIcon = leftIcon;

                if (fxTimer == null) {
                    fxTimer = new Timer(33, e -> {
                        swirlAngle += 0.05;
                        cloudOffset += 0.08;
                        if (panel != null)
                            panel.repaint();
                    });
                    fxTimer.start();
                }

                panel = new JPanel(null) {
                    @Override
                    protected void paintComponent(Graphics g) {
                        Graphics2D g2 = (Graphics2D) g.create();

                        // 1. 开启边缘抗锯齿（对代码画的圆角、线条生效）
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                        // 2. 【核心修复】开启双三次插值算法（专门针对图片缩放，消除图片锯齿和马赛克）
                        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                RenderingHints.VALUE_INTERPOLATION_BICUBIC);

                        // 3. 开启整体高质量渲染模式
                        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                        int w = getWidth(), h = getHeight();

                        g2.setColor(new Color(25, 30, 35, 230));
                        g2.fillRoundRect(0, 0, w, h, 20, 20);
                        g2.setColor(new Color(65, 140, 200, 150));
                        g2.setStroke(new BasicStroke(1.5f));
                        g2.drawRoundRect(1, 1, w - 2, h - 2, 20, 20);

                        int vw = 28;
                        int paddingLeft = vw / 2;
                        if (finalLeftIcon != null) {
                            g2.drawImage(finalLeftIcon, paddingLeft, (h - vw) / 2, vw, vw, this);
                        }

                        // 【新增】：在缩放状态下，绘制中央的静音/解除静音图标
                        if (!isExpanded) {
                            int muteX = 50;
                            int muteY = (h - vw) / 2;
                            int offset = 1; // 光圈向外扩张 4 像素，比例最贴合

                            // 这里的逻辑和颜色与右侧同心圆完全同步
                            if (isHoveringMute) {
                                g2.setColor(new Color(255, 255, 255, 200)); // 悬停高亮，完全一致
                                g2.setStroke(new BasicStroke(1.5f)); // 粗细 1.5f，完全一致
                                g2.drawOval(muteX - offset, muteY - offset, vw + offset * 2, vw + offset * 2);
                            }

                            Image audioImg = AppState.isVoiceEnabled ? ttsOnImg : ttsOffImg;
                            if (audioImg != null) {
                                g2.drawImage(audioImg, muteX, muteY, vw, vw, this);
                            }
                        }

                        int circleRadius = 16;
                        int circleX = w - 95;
                        int circleY = (h - circleRadius * 2) / 2;

                        Graphics2D g2Circle = (Graphics2D) g2.create();
                        g2Circle.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2Circle.clip(new Ellipse2D.Double(circleX, circleY, circleRadius * 2, circleRadius * 2));

                        g2Circle.setColor(new Color(0, 40, 80));
                        g2Circle.fillRect(circleX, circleY, circleRadius * 2, circleRadius * 2);

                        Point2D center1 = new Point2D.Double(circleX + circleRadius + Math.sin(cloudOffset) * 5,
                                circleY + circleRadius + Math.cos(cloudOffset) * 5);
                        RadialGradientPaint rp1 = new RadialGradientPaint(center1, circleRadius * 1.5f,
                                new float[] { 0f, 1f },
                                new Color[] { new Color(0, 255, 200, 180), new Color(0, 100, 200, 0) });
                        g2Circle.setPaint(rp1);
                        g2Circle.fillRect(circleX, circleY, circleRadius * 2, circleRadius * 2);

                        Point2D center2 = new Point2D.Double(circleX + circleRadius + Math.cos(cloudOffset * 0.8) * 6,
                                circleY + circleRadius + Math.sin(cloudOffset * 0.8) * 6);
                        RadialGradientPaint rp2 = new RadialGradientPaint(center2, circleRadius * 1.2f,
                                new float[] { 0f, 1f },
                                new Color[] { new Color(65, 140, 255, 150), new Color(0, 50, 150, 0) });

                        g2Circle.translate(circleX + circleRadius, circleY + circleRadius);
                        g2Circle.rotate(swirlAngle);
                        g2Circle.translate(-(circleX + circleRadius), -(circleY + circleRadius));
                        g2Circle.setPaint(rp2);
                        g2Circle.fillRect(circleX, circleY, circleRadius * 2, circleRadius * 2);
                        g2Circle.dispose();

                        if (isHoveringExport) {
                            g2.setColor(new Color(255, 255, 255, 200)); // 悬停高亮
                        } else {
                            g2.setColor(new Color(255, 255, 255, 120)); // 平时半透明
                        }
                        g2.setStroke(new BasicStroke(1.5f));
                        g2.drawOval(circleX + 2, circleY + 2, circleRadius * 2 - 4, circleRadius * 2 - 4);

                        String numStr = String.valueOf(loadedDataCount);
                        g2.setColor(Color.WHITE);
                        g2.setFont(new Font("Arial", Font.BOLD, 17));
                        FontMetrics numFm = g2.getFontMetrics();
                        int dia = circleRadius * 2;
                        int numX = circleX + (dia - numFm.stringWidth(numStr)) / 2;
                        int numY = circleY + (dia / 2) + ((numFm.getAscent() - numFm.getDescent()) / 2);
                        g2.drawString(numStr, numX, numY);
                        g2.dispose();
                    }
                };
                panel.setOpaque(false);

                clipContainerRef = new JPanel(null);
                clipContainerRef.setOpaque(false);
                panel.add(clipContainerRef);

                JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 13));
                buttonRow.setBounds(0, 0, 800, 50);
                buttonRow.setOpaque(false);
                clipContainerRef.add(buttonRow);

                Color fontColor = new Color(220, 220, 220);
                Font uiFont = new Font("微软雅黑", Font.PLAIN, 12);

                voiceBtnRef = new JToggleButton(AppState.isVoiceEnabled ? "播报(开)" : "播报(关)");
                styleToggleBtn(voiceBtnRef);
                voiceBtnRef.setSelected(AppState.isVoiceEnabled);
                JToggleButton bannerBtn = new JToggleButton(AppState.isBannerEnabled ? "条幅(开)" : "条幅(关)");
                styleToggleBtn(bannerBtn);
                bannerBtn.setSelected(AppState.isBannerEnabled);

                JRadioButton rbFull = createRadio("完整", AppState.verbosity == 0, fontColor, uiFont);
                JRadioButton rbConcise = createRadio("精简", AppState.verbosity == 1, fontColor, uiFont);
                JRadioButton rbMinimal = createRadio("极简", AppState.verbosity == 2, fontColor, uiFont);
                ButtonGroup bgVerbosity = new ButtonGroup();
                bgVerbosity.add(rbFull);
                bgVerbosity.add(rbConcise);
                bgVerbosity.add(rbMinimal);

                rbFull.addActionListener(e -> ConfigManager.set("verbosity", String.valueOf(AppState.verbosity = 0)));
                rbConcise
                        .addActionListener(e -> ConfigManager.set("verbosity", String.valueOf(AppState.verbosity = 1)));
                rbMinimal
                        .addActionListener(e -> ConfigManager.set("verbosity", String.valueOf(AppState.verbosity = 2)));

                voiceBtnRef.addItemListener(e -> {
                    AppState.isVoiceEnabled = voiceBtnRef.isSelected();
                    voiceBtnRef.setText(AppState.isVoiceEnabled ? "播报(开)" : "播报(关)");
                    ConfigManager.set("voiceEnabled", String.valueOf(AppState.isVoiceEnabled));
                    rbFull.setEnabled(AppState.isVoiceEnabled);
                    rbConcise.setEnabled(AppState.isVoiceEnabled);
                    rbMinimal.setEnabled(AppState.isVoiceEnabled);
                    panel.repaint();
                });

                bannerBtn.addItemListener(e -> {
                    AppState.isBannerEnabled = bannerBtn.isSelected();
                    bannerBtn.setText(AppState.isBannerEnabled ? "条幅(开)" : "条幅(关)");
                    ConfigManager.set("bannerEnabled", String.valueOf(AppState.isBannerEnabled));
                });

                JCheckBox cbNew = createCheckBox("新订单", AppState.notifyNewOrder, fontColor, uiFont);
                JCheckBox cbCancel = createCheckBox("订单取消", AppState.notifyCancel, fontColor, uiFont);
                JCheckBox cbRemove = createCheckBox("订单移除", AppState.notifyRemove, fontColor, uiFont);
                cbNew.addItemListener(e -> ConfigManager.set("notifyNew",
                        String.valueOf(AppState.notifyNewOrder = cbNew.isSelected())));
                cbCancel.addItemListener(e -> ConfigManager.set("notifyCancel",
                        String.valueOf(AppState.notifyCancel = cbCancel.isSelected())));
                cbRemove.addItemListener(e -> ConfigManager.set("notifyRemove",
                        String.valueOf(AppState.notifyRemove = cbRemove.isSelected())));

                buttonRow.add(voiceBtnRef);
                buttonRow.add(bannerBtn);
                buttonRow.add(rbFull);
                buttonRow.add(rbConcise);
                buttonRow.add(rbMinimal);
                JLabel sep2 = new JLabel("|");
                sep2.setForeground(Color.GRAY);
                buttonRow.add(sep2);
                buttonRow.add(cbNew);
                buttonRow.add(cbCancel);
                buttonRow.add(cbRemove);

                pickerRef = new PositionPicker();
                panel.add(pickerRef);

                closeBtnRef = new JLabel("×");
                closeBtnRef.setFont(new Font("微软雅黑", Font.BOLD, 18));
                closeBtnRef.setForeground(new Color(150, 150, 150));
                closeBtnRef.setCursor(new Cursor(Cursor.HAND_CURSOR));
                closeBtnRef.addMouseListener(new MouseAdapter() {
                    public void mouseEntered(MouseEvent e) {
                        closeBtnRef.setForeground(new Color(255, 80, 80));
                    }

                    public void mouseExited(MouseEvent e) {
                        closeBtnRef.setForeground(new Color(150, 150, 150));
                    }

                    public void mouseClicked(MouseEvent e) {
                        System.exit(0);
                    }
                });
                panel.add(closeBtnRef);

                applyLayoutInternal();

                // 【重要】唯一的核心鼠标移动监听，增加静音按钮的Hover判定
                panel.addMouseMotionListener(new MouseMotionAdapter() {
                    private boolean lockExport = false;
                    private boolean lockLogo = false;
                    private boolean lockMute = false;

                    public void mouseMoved(MouseEvent e) {
                        int x = e.getX();
                        int y = e.getY();
                        int circleX = panel.getWidth() - 95;

                        // 根据 vw=28 重新计算的精准触碰范围 (Y轴中心点是 25，上下浮动 14 = 11 到 39)
                        boolean inExport = (x >= circleX && x <= circleX + 32 && y >= 9 && y <= 41);
                        boolean inLogo = (x >= 14 && x <= 42 && y >= 11 && y <= 39);
                        boolean inMute = (!isExpanded && x >= 50 && x <= 78 && y >= 11 && y <= 39);

                        // Export 区 (同心圆)
                        if (inExport && !lockExport) {
                            lockExport = true;
                            panel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                            isHoveringExport = true;
                        } else if (!inExport && lockExport) {
                            lockExport = false;
                            if (!inLogo && !inMute)
                                panel.setCursor(Cursor.getDefaultCursor());
                            isHoveringExport = false;
                        }

                        // Logo 区 (最左侧伸缩)
                        if (inLogo && !lockLogo) {
                            lockLogo = true;
                            panel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                        } else if (!inLogo && lockLogo) {
                            lockLogo = false;
                            if (!inExport && !inMute)
                                panel.setCursor(Cursor.getDefaultCursor());
                        }

                        // Mute 图标区 (静音按钮)
                        if (inMute && !lockMute) {
                            lockMute = true;
                            panel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                            isHoveringMute = true; // <--- 必须只在 inMute 里触发！
                        } else if (!inMute && lockMute) {
                            lockMute = false;
                            if (!inExport && !inLogo)
                                panel.setCursor(Cursor.getDefaultCursor());
                            isHoveringMute = false; // <--- 离开时关闭
                        }
                    }
                });

                panel.addMouseListener(new MouseAdapter() {
                    public void mouseClicked(MouseEvent e) {
                        int x = e.getX();
                        int y = e.getY();

                        // 1. 左侧 Logo 点击区域 (基于 14~42)
                        if (x >= 14 && x <= 42 && y >= 11 && y <= 39) {
                            if (SwingUtilities.isLeftMouseButton(e)) {
                                toggleExpand();
                                idleTimeMillis = 0;
                            }
                        }

                        // 2. 缩放状态下：中间静音图标点击区域 (基于 50~78)
                        if (!isExpanded && x >= 50 && x <= 78 && y >= 11 && y <= 39) {
                            if (SwingUtilities.isLeftMouseButton(e)) {
                                AppState.isVoiceEnabled = !AppState.isVoiceEnabled;
                                ConfigManager.set("voiceEnabled", String.valueOf(AppState.isVoiceEnabled));
                                if (voiceBtnRef != null) {
                                    voiceBtnRef.setSelected(AppState.isVoiceEnabled);
                                    voiceBtnRef.setText(AppState.isVoiceEnabled ? "播报(开)" : "播报(关)");
                                }
                                panel.repaint(); // 点击后立刻刷新图标状态
                            }
                        }

                        // 3. 右侧同心圆 点击区域
                        int circleX = panel.getWidth() - 95;
                        if (x >= circleX && x <= circleX + 32 && y >= 9 && y <= 41) {
                            if (SwingUtilities.isLeftMouseButton(e)) {
                                if (isExporting) {
                                    BannerUI.showBanner(
                                            "<html>正在生成数据中，此过程可能需要一定时间，请稍后（<font color='#FF6B6B'><b>请勿反复重复点击</b></font>）...</html>");
                                    return;
                                }
                                isExporting = true;
                                BannerUI.showBanner("正在生成数据中，此过程可能需要一定时间，请稍后...");
                                new Thread(() -> {
                                    try {
                                        exportNoDriverOrders();
                                    } finally {
                                        isExporting = false;
                                    }
                                }).start();
                            } else if (SwingUtilities.isRightMouseButton(e)) {
                                try {
                                    String targetUrl = ConfigManager.get("watchUrl", watchUrl);
                                    if (Desktop.isDesktopSupported()
                                            && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                                        Desktop.getDesktop().browse(new java.net.URI(targetUrl));
                                    } else {
                                        BannerUI.showBanner("当前系统不支持自动打开浏览器");
                                    }
                                } catch (Exception ex) {
                                }
                            }
                        }
                    }
                });

                widget.setContentPane(panel);
                widget.setVisible(true);

                // 【新增】启动全局防抖呼吸灯与缩放监控
                setupIdleTimer();
            });
        }

        // ======================= 新增：动画与生命周期逻辑 =======================

        private static void toggleExpand() {
            isExpanded = !isExpanded;
            int targetW = isExpanded ? EXPANDED_WIDTH : COLLAPSED_WIDTH;
            if (animTimer != null && animTimer.isRunning())
                animTimer.stop();

            animTimer = new Timer(10, ae -> {
                int step = (targetW - currentWidth) / 6;
                if (step == 0)
                    step = (targetW > currentWidth) ? 2 : -2;
                currentWidth += step;

                if ((step > 0 && currentWidth >= targetW) || (step < 0 && currentWidth <= targetW)) {
                    currentWidth = targetW;
                    animTimer.stop();
                }
                applyLayoutInternal();
                widget.setBounds(calculateBounds(currentWidth, 50));
                panel.repaint();
            });
            animTimer.start();
        }

        private static void applyLayoutInternal() {
            if (pickerRef == null || closeBtnRef == null || clipContainerRef == null)
                return;
            int w = currentWidth;

            // 实时更新右侧按钮的坐标，保持它们紧贴右边缘
            pickerRef.setBounds(w - 50, 16, 18, 18);
            closeBtnRef.setBounds(w - 25, 10, 20, 30);

            // 【核心裁切逻辑】：按比例动态滑动，展开时给足580px，折叠时完美归零防留痕
            // 假设你的 EXPANDED_WIDTH 已经改成了 720，COLLAPSED_WIDTH 是 190
            int maxClipW = EXPANDED_WIDTH - 140;
            int clipW = (int) ((w - COLLAPSED_WIDTH) * ((double) maxClipW / (EXPANDED_WIDTH - COLLAPSED_WIDTH)));

            if (clipW < 0)
                clipW = 0;
            clipContainerRef.setBounds(45, 0, clipW, 50);

            // 【修复消失Bug】：强制确保它们始终可见，去掉了之前自作聪明的隐藏逻辑
            if (!pickerRef.isVisible())
                pickerRef.setVisible(true);
            if (!closeBtnRef.isVisible())
                closeBtnRef.setVisible(true);
        }

        private static void setupIdleTimer() {
            if (idleTimer != null)
                return;
            idleTimer = new Timer(100, e -> {
                if (widget == null || !widget.isVisible())
                    return;
                PointerInfo pi = MouseInfo.getPointerInfo();
                if (pi != null) {
                    Point mouseLoc = pi.getLocation();
                    Rectangle bounds = widget.getBounds();
                    bounds.grow(10, 10); // 增加10像素的宽容度，防止在边缘微小抖动时闪烁

                    if (bounds.contains(mouseLoc)) {
                        idleTimeMillis = 0;
                        if (currentOpacity < 1.0f) {
                            unfadeSmoothly();
                        }
                    } else {
                        idleTimeMillis += 100;
                        if (isExpanded) {
                            if (idleTimeMillis >= 5000) { // 离开5秒后自动缩放
                                toggleExpand();
                                idleTimeMillis = 0;
                            }
                        } else {
                            if (idleTimeMillis >= 5000 && currentOpacity >= 0.99f) { // 缩放状态下再等5秒淡出
                                fadeSmoothly();
                            }
                        }
                    }
                }
            });
            idleTimer.start();
        }

        private static void fadeSmoothly() {
            if (alphaTimer != null && alphaTimer.isRunning())
                return;
            if (currentOpacity <= 0.3f)
                return;
            alphaTimer = new Timer(30, e -> {
                currentOpacity -= 0.05f;
                if (currentOpacity <= 0.3f) {
                    currentOpacity = 0.3f; // 保持 30% 最低透明度，确保依然隐约可见
                    alphaTimer.stop();
                }
                if (widget != null)
                    widget.setOpacity(currentOpacity);
            });
            alphaTimer.start();
        }

        private static void unfadeSmoothly() {
            if (alphaTimer != null && alphaTimer.isRunning())
                alphaTimer.stop();
            if (currentOpacity >= 1.0f) {
                currentOpacity = 1.0f;
                if (widget != null)
                    widget.setOpacity(1.0f);
                return;
            }
            alphaTimer = new Timer(20, e -> {
                currentOpacity += 0.1f;
                if (currentOpacity >= 1.0f) {
                    currentOpacity = 1.0f;
                    alphaTimer.stop();
                }
                if (widget != null)
                    widget.setOpacity(currentOpacity);
            });
            alphaTimer.start();
        }

        // ======================= 原有辅助方法 =======================

        private static void styleToggleBtn(JToggleButton btn) {
            btn.setFocusPainted(false);
            btn.setBorderPainted(false);
            btn.setContentAreaFilled(false);
            btn.setOpaque(false);
            btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
            btn.setForeground(Color.WHITE);
            btn.setFont(new Font("微软雅黑", Font.BOLD, 13));
            btn.setUI(new javax.swing.plaf.basic.BasicButtonUI() {
                public void paint(Graphics g, JComponent c) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(btn.isSelected() ? new Color(65, 140, 200) : new Color(0, 74, 119));
                    g2.fillRoundRect(0, 0, btn.getWidth(), btn.getHeight(), 12, 12);
                    super.paint(g2, c);
                    g2.dispose();
                }
            });
        }

        private static JRadioButton createRadio(String text, boolean selected, Color c, Font f) {
            JRadioButton rb = new JRadioButton(text, selected);
            rb.setOpaque(false);
            rb.setForeground(c);
            rb.setFont(f);
            rb.setFocusPainted(false);
            return rb;
        }

        private static JCheckBox createCheckBox(String text, boolean selected, Color c, Font f) {
            JCheckBox cb = new JCheckBox(text, selected);
            cb.setOpaque(false);
            cb.setForeground(c);
            cb.setFont(f);
            cb.setFocusPainted(false);
            return cb;
        }
    }

    // ================= 四叶草位置控制器 =================
    public static class PositionPicker extends JPanel {
        public PositionPicker() {
            setPreferredSize(new Dimension(18, 18));
            setOpaque(false);
            setCursor(new Cursor(Cursor.HAND_CURSOR));
            setToolTipText("点击四个角可将控制条移动到屏幕对应的角落");
            addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    boolean left = e.getX() < getWidth() / 2;
                    boolean top = e.getY() < getHeight() / 2;
                    if (top && left)
                        AppState.widgetPosition = "TL";
                    else if (top && !left)
                        AppState.widgetPosition = "TR";
                    else if (!top && left)
                        AppState.widgetPosition = "BL";
                    else
                        AppState.widgetPosition = "BR";
                    ConfigManager.set("widgetPosition", AppState.widgetPosition);
                    ControlWidget.updateLocation();
                    repaint();
                }
            });
        }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int size = 7;
            int gap = 3;
            Color active = new Color(65, 140, 200);
            Color inactive = new Color(100, 100, 100);
            g2.setColor(AppState.widgetPosition.equals("TL") ? active : inactive);
            g2.fillRoundRect(0, 0, size, size, 3, 3);
            g2.setColor(AppState.widgetPosition.equals("TR") ? active : inactive);
            g2.fillRoundRect(size + gap, 0, size, size, 3, 3);
            g2.setColor(AppState.widgetPosition.equals("BL") ? active : inactive);
            g2.fillRoundRect(0, size + gap, size, size, 3, 3);
            g2.setColor(AppState.widgetPosition.equals("BR") ? active : inactive);
            g2.fillRoundRect(size + gap, size + gap, size, size, 3, 3);
            g2.dispose();
        }
    }

    // ================= 配置管理器 =================
    public static class ConfigManager {
        private static Properties props = new Properties();

        public static void init() {
            try (InputStream fis = new FileInputStream(new File(CONFIG_FILE));
                    InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8)) {
                props.load(isr);
            } catch (Exception e) {
            }
        }

        public static String get(String k, String d) {
            return props.getProperty(k, d);
        }

        public static void set(String k, String v) {
            props.setProperty(k, v);
            try (OutputStream os = new FileOutputStream(CONFIG_FILE)) {
                props.store(os, "");
            } catch (Exception ignored) {
            }
        }
    }

    // ================= 加载 UI =================
    public static class LoadingUI {
        private static JWindow window;
        private static JLabel textLabel;
        private static Timer timer;
        private static int angle = 0;

        public static void show(String message) {
            SwingUtilities.invokeLater(() -> {
                if (window == null) {
                    window = new JWindow();
                    window.setSize(300, 160);
                    window.setLocationRelativeTo(null);
                    window.setBackground(new Color(0, 0, 0, 0));
                    window.setAlwaysOnTop(true);
                    JPanel panel = new JPanel() {
                        protected void paintComponent(Graphics g) {
                            Graphics2D g2 = (Graphics2D) g.create();
                            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                            g2.setColor(new Color(20, 25, 30, 240));
                            g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 15, 15);
                            g2.setColor(new Color(65, 140, 200));
                            g2.setStroke(new BasicStroke(3f));
                            g2.drawArc(getWidth() / 2 - 20, 30, 40, 40, angle, 270);
                            g2.dispose();
                        }
                    };
                    panel.setLayout(null);
                    textLabel = new JLabel("", SwingConstants.CENTER);
                    textLabel.setForeground(Color.WHITE);
                    textLabel.setFont(new Font("微软雅黑", Font.PLAIN, 13));
                    textLabel.setBounds(10, 90, 280, 30);
                    panel.add(textLabel);
                    window.setContentPane(panel);
                }
                textLabel.setText(message);
                window.setVisible(true);
                if (timer == null)
                    timer = new Timer(20, e -> {
                        angle = (angle + 10) % 360;
                        window.repaint();
                    });
                if (!timer.isRunning())
                    timer.start();
            });
        }

        public static void hide() {
            SwingUtilities.invokeLater(() -> {
                if (window != null)
                    window.setVisible(false);
                if (timer != null)
                    timer.stop();
            });
        }
    }

    // ================= Cookie 配置对话框 =================
    public static class CookieDialog extends JDialog {
        private static String finalCookie = null;

        public static String showDialog() {
            finalCookie = null;
            new CookieDialog().setVisible(true);
            return finalCookie;
        }

        private CookieDialog() {
            setModal(true);
            setAlwaysOnTop(true);
            setUndecorated(true);
            setSize(500, 300);
            setLocationRelativeTo(null);
            setBackground(new Color(0, 0, 0, 0));
            JPanel mainPanel = new JPanel() {
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(new Color(30, 35, 40, 240));
                    g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 20, 20);
                    g2.dispose();
                }
            };
            mainPanel.setLayout(null);
            JLabel titleLabel = new JLabel("检测到登录失效，请手动填入新的 Cookie", SwingConstants.CENTER);
            titleLabel.setForeground(new Color(220, 220, 220));
            titleLabel.setFont(new Font("微软雅黑", Font.BOLD, 16));
            titleLabel.setBounds(20, 15, 460, 30);
            mainPanel.add(titleLabel);
            JTextArea cookieArea = new JTextArea();
            cookieArea.setLineWrap(true);
            cookieArea.setBackground(new Color(20, 24, 28));
            cookieArea.setForeground(Color.WHITE);
            cookieArea.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                        if (cookieArea.getText().trim().isEmpty()) {
                            try {
                                Transferable t = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
                                if (t != null && t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                                    String data = (String) t.getTransferData(DataFlavor.stringFlavor);

                                    // 核心校验：必须包含 "jsj_uid=" 字眼才允许粘贴
                                    if (data != null && data.contains("jsj_uid=")) {
                                        cookieArea.setText(data);
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            });
            JScrollPane scrollPane = new JScrollPane(cookieArea);
            scrollPane.setBounds(20, 55, 460, 160);
            scrollPane.setBorder(null);
            mainPanel.add(scrollPane);
            JButton saveBtn = new JButton("开始查询");
            saveBtn.setBounds(140, 240, 100, 35);
            saveBtn.setBackground(new Color(65, 140, 200));
            saveBtn.setForeground(Color.WHITE);
            saveBtn.setFocusPainted(false);
            saveBtn.addActionListener(e -> {
                finalCookie = cookieArea.getText();
                dispose();
            });
            mainPanel.add(saveBtn);
            JButton cancelBtn = new JButton("关闭程序");
            cancelBtn.setBounds(260, 240, 100, 35);
            cancelBtn.setBackground(new Color(180, 80, 90));
            cancelBtn.setForeground(Color.WHITE);
            cancelBtn.setFocusPainted(false);
            cancelBtn.addActionListener(e -> {
                finalCookie = null;
                dispose();
            });
            mainPanel.add(cancelBtn);
            add(mainPanel);
        }
    }
}