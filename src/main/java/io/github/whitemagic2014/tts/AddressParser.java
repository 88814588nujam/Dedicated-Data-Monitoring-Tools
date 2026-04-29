package io.github.whitemagic2014.tts;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.houbb.opencc4j.util.ZhConverterUtil;

public class AddressParser {

    // --- 本地多语言区域映射表 ---
    private static final Map<String, String> REGION_MAP = new HashMap<>();

    static {
        // 东京 23 区 (包含 简/繁/日/英/韩/泰)
        addRegion("千代田", "千代田", "Chiyoda", "치요다", "ชิโยดะ");
        addRegion("中央", "中央", "Chuo", "Chūō", "주오", "츄오", "ชูโอ");
        addRegion("港", "港", "Minato", "미나토", "มินาโตะ");
        addRegion("新宿", "新宿", "Shinjuku", "신주쿠", "ชินจูกุ");
        addRegion("文京", "文京", "Bunkyo", "분쿄", "บุนเคียว");
        addRegion("台东", "台東", "Taito", "타이토", "ไทโตะ");
        addRegion("墨田", "墨田", "Sumida", "스미다", "สุมิดะ");
        addRegion("江东", "江東", "Koto", "고토", "코토", "โกโตะ");
        addRegion("品川", "品川", "Shinagawa", "시나가와", "ชินางาวะ");
        addRegion("目黑", "目黒", "目黑", "Meguro", "메구로", "เมกูโระ");
        addRegion("大田", "大田", "Ota", "오타", "โอตะ");
        addRegion("世田谷", "世田谷", "Setagaya", "세타가야", "เซตางายะ");
        addRegion("涩谷", "澀谷", "渋谷", "Shibuya", "시부야", "ชิบูย่า", "ชิบุยะ");
        addRegion("中野", "中野", "Nakano", "나카노", "นากาโนะ");
        addRegion("杉并", "杉並", "Suginami", "스기나미", "ซูงินามิ");
        addRegion("丰岛", "豊島", "丰岛", "Toshima", "도시마", "토시마", "โทชิมะ");
        addRegion("北", "北", "Kita", "기타구", "키타구", "คิตะ");
        addRegion("荒川", "荒川", "Arakawa", "아라카와", "อารากาวะ");
        addRegion("板桥", "板橋", "Itabashi", "이타바시", "อิตาบาชิ");
        addRegion("练马", "練馬", "Nerima", "네리마", "เนริมะ");
        addRegion("足立", "足立", "Adachi", "아다치", "อาดะจิ", "อาดาจิ");
        addRegion("葛饰", "葛飾", "Katsushika", "가쓰시카", "카츠시카", "คัตสึชิกะ");
        addRegion("江户川", "江戸川", "Edogawa", "에도가와", "เอโดงาวะ");
        
        // 周边热门
        addRegion("浦安", "浦安", "Urayasu", "우라야스", "อุรายาสุ");
        addRegion("松户", "松戸", "Matsudo", "마쓰도", "마츠도", "มัตสึโดะ");
        addRegion("印西", "印西", "Inzai", "인자이", "อินไซ");
    }

    private static void addRegion(String standard, String... aliases) {
        REGION_MAP.put(standard.toLowerCase(), standard);
        for (String alias : aliases) {
            REGION_MAP.put(alias.toLowerCase(), standard);
        }
    }

    public static String getRegionSmart(String address) {
        if (address == null || address.trim().isEmpty())
            return "未知";

        String addrLower = address.toLowerCase();

        // --- 1. 本地关键词快速匹配 (最快, 零成本) ---
        for (Map.Entry<String, String> entry : REGION_MAP.entrySet()) {
            if (addrLower.contains(entry.getKey())) {
                // 找到匹配，补全后缀
                String name = entry.getValue();
                return name + (name.equals("港") || name.equals("北") ? "区" : "");
            }
        }

        // --- 2. 正则表达式提取 (处理如 "XX区" 的标准写法) ---
        Matcher m = Pattern.compile("([\\u4e00-\\u9fa5]+?[市区])").matcher(address);
        if (m.find()) {
            return ZhConverterUtil.toSimple(m.group(1));
        }

        // --- 3. 接口兜底 (仅针对泰国语地址或奇葩地址) ---
        // 建议在这里加上 1 秒延迟，保护 IP
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
        }

        return getChineseRegionFromApi(address);
    }

    public static String getChineseRegionFromApi(String address) {
        if (address == null || address.trim().isEmpty()) {
            return "未知";
        }

        try {
            // ==========================================
            // 1. 本地极速匹配 (专治规整的纯中/日文)
            // ==========================================
            Matcher mJp = Pattern.compile("([\\u4e00-\\u9fa5]+?[省道府県县都])?([\\u4e00-\\u9fa5]+?[市区])").matcher(address);
            if (mJp.find()) {
                String prov = mJp.group(1) == null ? "" : mJp.group(1);
                String city = mJp.group(2);
                return (prov + city).replace("東京都", "东京").replace("大阪府", "大阪");
            }

            // ==========================================
            // 2. 第一波：常规格式清洗
            // ==========================================
            String query1 = address.replaceAll("[\\(（\\[【\\{].*$", "");
            query1 = query1.replaceAll("〒?\\d{3}-\\d{4}", " ");

            Matcher mEn = Pattern.compile("([A-Za-z\\s\\-]+(?:City|Ku|Shi|Ward))\\s*,\\s*([A-Za-z\\s\\-]+)")
                    .matcher(query1);
            if (mEn.find()) {
                String cityPart = mEn.group(1).replaceAll("-*\\s*(?i)\\b(City|Ward|Ku|Shi)\\b", "").trim();
                String prefPart = mEn.group(2).trim();
                query1 = cityPart + " " + prefPart;
            }
            query1 = query1.replaceAll("[,\\-]", " ").replaceAll("\\s+", " ").trim();

            String parsedResult = "";
            if (!query1.isEmpty()) {
                String jsonStr = requestNominatim(query1);
                parsedResult = parseNominatimJson(jsonStr);
            }

            // ==========================================
            // 3. 第二波 X 光扫描 (专治括号乱套、大杂烩)
            // ==========================================
            // 当第一波解析失败，或者第一波把核心信息误删时，自动触发！
            if (parsedResult.isEmpty()) {
                // 提前消灭 chome(丁目) 这种干扰词
                String kwSource = address.replaceAll("(?i)ch[oō]me", " ");

                // X光吸尘器：只吸取长度 >= 3 的纯英文字母单词
                Matcher mKw = Pattern.compile("[A-Za-z]{3,}").matcher(kwSource);
                StringBuilder kwBuilder = new StringBuilder();
                while (mKw.find()) {
                    String w = mKw.group();
                    // 过滤掉建筑词汇和无意义介词，只留最核心的地理名词
                    if (!w.toLowerCase().matches(
                            "hotel|inn|hostel|japan|city|ward|ku|shi|premier|the|of|and|in|at|room|floor|building|plaza|dia|daiwa|roynet|nestay|dai")) {
                        kwBuilder.append(w).append(" ");
                    }
                }
                String query2 = kwBuilder.toString().trim();

                // 对于你的新地址，这里 query2 会完美提取出 "Tokyo Toshima Sugamo"
                if (!query2.isEmpty() && !query2.equals(query1)) {
                    String jsonStr2 = requestNominatim(query2);
                    parsedResult = parseNominatimJson(jsonStr2);
                }
            }
            String rtAdd = parsedResult.isEmpty() ? "【" + address + "】"
                    : deduplicate(convertToSimplified(parsedResult)).replace("区", "");
            return rtAdd;

        } catch (Exception e) {
            return "异常:" + e.getMessage();
        }
    }

    public static String convertToSimplified(String text) {
        if (text == null || text.isEmpty())
            return text;
        // toSimple 会自动处理繁体到简体的映射，包括罕见地名
        return ZhConverterUtil.toSimple(text);
    }

    private static String deduplicate(String text) {
        if (text == null || text.isEmpty())
            return text;

        // 这个正则的意思是：匹配连续出现的两个及以上相同的汉字词组，中间允许有空格或斜杠
        // ([\u4e00-\u9fa5]{2,}) 匹配至少2个汉字并捕获
        // [\s/]* 匹配中间可能出现的空格或斜杠
        // \1 匹配前面捕获的同一个词组
        String regex = "([\\u4e00-\\u9fa5]{2,})[\\s/]*\\1";

        String last;
        String current = text;
        // 循环执行，防止出现“东京东京东京”这种三连叠词
        do {
            last = current;
            current = current.replaceAll(regex, "$1");
        } while (!current.equals(last));

        return current;
    }

    // --- 独立的 JSON 解析模块 ---
    private static String parseNominatimJson(String jsonStr) {
        if (jsonStr == null || jsonStr.equals("[]"))
            return "";

        String province = getJsonValue(jsonStr, "province");
        String state = getJsonValue(jsonStr, "state");
        String city = getJsonValue(jsonStr, "city");
        String borough = getJsonValue(jsonStr, "borough");
        String cityDistrict = getJsonValue(jsonStr, "city_district");
        String county = getJsonValue(jsonStr, "county");
        String town = getJsonValue(jsonStr, "town");
        String suburb = getJsonValue(jsonStr, "suburb");

        String level1 = firstNonNull(province, state);
        String level2 = firstNonNull(city, borough, cityDistrict, county, town, suburb);

        String finalResult = level1 + level2;
        if (level1.equals(level2))
            finalResult = level1;

        return finalResult.replace("东京都", "东京").replace("大阪府", "大阪");
    }

    // --- 独立的网络请求模块 ---
    private static String requestNominatim(String query) {
        try {
            String encodedAddress = URLEncoder.encode(query, "UTF-8");
            String urlStr = "https://nominatim.openstreetmap.org/search?q=" + encodedAddress
                    + "&format=json&addressdetails=1&limit=1";
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) JinshujuApp/4.0");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            if (conn.getResponseCode() != 200)
                return "[]";

            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null)
                response.append(line);
            in.close();
            return response.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String getJsonValue(String json, String key) {
        String regex = "\"" + key + "\"\\s*:\\s*\"([^\"]+)\"";
        Matcher matcher = Pattern.compile(regex).matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private static String firstNonNull(String... values) {
        for (String val : values) {
            if (val != null && !val.trim().isEmpty()) {
                return val.trim();
            }
        }
        return "";
    }

    public static void main(String[] args) {
        String add = "東京都Chūō役所(1-chōme-4-1 江戸川区, Edogawa City, Tokyo 132-8501日本)";
        System.out.println(getRegionSmart(add));
    }
}