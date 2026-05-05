package io.github.whitemagic2014.tts;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.houbb.opencc4j.util.ZhConverterUtil;

public class AddressParser {

    // --- 本地多语言区域映射表 ---
    private static final Map<String, String> REGION_MAP = new HashMap<>();

    static {
        // 东京 23 区
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

        // 横浜
        addRegion("横滨",
                // 基础名称
                "横滨", "横浜", "Yokohama", "요코하마", "โยโกฮาม่า", "横滨市", "横浜市",
                // 各区名称 (包含中文、日文繁体/异体字、英文罗马音)
                "西区", "Nishi", "Nishi-ku",
                "中区", "Naka", "Naka-ku",
                "南区", "Minami", "Minami-ku",
                "港北区", "Kohoku", "Kohoku-ku",
                "鹤见区", "鶴見区", "Tsurumi", "Tsurumi-ku",
                "神奈川区", "Kanagawa", "Kanagawa-ku",
                "保土谷区", "保土ケ谷区", "Hodogaya", "Hodogaya-ku",
                "矶子区", "磯子区", "Isogo", "Isogo-ku",
                "金泽区", "金沢区", "Kanazawa", "Kanazawa-ku",
                "港南区", "Konan", "Konan-ku",
                "户冢区", "戸塚区", "Totsuka", "Totsuka-ku",
                "荣区", "栄区", "Sakae", "Sakae-ku",
                "泉区", "Izumi", "Izumi-ku",
                "濑谷区", "瀬谷区", "Seya", "Seya-ku",
                "旭区", "Asahi", "Asahi-ku",
                "绿区", "緑区", "Midori", "Midori-ku",
                "青叶区", "青葉区", "Aoba", "Aoba-ku",
                "都筑区", "Tsuzuki", "Tsuzuki-ku");
    }

    // --- 本地热门商圈/地标映射表 (特征词 -> 标准区名) ---
    private static final Map<String, String> LANDMARK_MAP = new HashMap<>();

    static {
        // 丰岛区 (Toshima) - 三大副都心之一，包含池袋、巢鸭等
        addLandmark("丰岛", "池袋", "ikebukuro", "이케부쿠로", "巢鸭", "sugamo", "스가모", "目白", "mejiro");

        // 中央区 (Chuo) - 商业重镇
        addLandmark("中央", "银座", "銀座", "ginza", "긴자", "京桥", "京橋", "kyobashi", "교바시",
                "日本桥", "日本橋", "nihonbashi", "니혼바시", "筑地", "築地", "tsukiji", "쓰키지", "月岛", "tsukishima");

        // 千代田区 (Chiyoda) - 政治与二次元中心
        addLandmark("千代田", "秋叶原", "秋葉原", "akihabara", "아키하바라", "丸之内", "丸の内", "marunouchi", "마루노우치",
                "神田", "kanda", "간다", "有乐町", "有楽町", "yurakucho", "우라쿠초", "大手町", "otemachi");

        // 港区 (Minato) - 富人区与外企聚集地
        addLandmark("港", "六本木", "roppongi", "롯폰기", "台场", "お台場", "odaiba", "오다이바",
                "赤坂", "akasaka", "아카사카", "新桥", "新橋", "shimbashi", "신바시", "虎之门", "虎ノ門", "toranomon",
                "麻布", "azabu", "아자부", "青山", "aoyama", "아오야마", "表参道", "omotesando", "오모테산도");

        // 涩谷区 (Shibuya) - 潮流中心
        addLandmark("涩谷", "代代木", "yoyogi", "요요기", "原宿", "harajuku", "하라주쿠",
                "惠比寿", "恵比寿", "ebisu", "에비스", "代官山", "daikanyama", "다이칸야마", "广尾", "hiroo");

        // 新宿区 (Shinjuku) - 最繁忙的枢纽
        addLandmark("新宿", "歌舞伎町", "kabukicho", "가부키초", "高田马场", "高田馬場", "takadanobaba", "다카다노바바",
                "神乐坂", "神楽坂", "kagurazaka", "카구라자카", "四谷", "yotsuya", "요쓰야", "大久保", "okubo", "오쿠보");

        // 台东区 (Taito) - 传统文化区
        addLandmark("台东", "浅草", "asakusa", "아사쿠사", "上野", "ueno", "우에노", "藏前", "kuramae", "日暮里", "nippori");

        // 墨田区 (Sumida) - 晴空塔所在地
        addLandmark("墨田", "押上", "oshiage", "오시아게", "锦系町", "錦糸町", "kinshicho", "킨시초", "晴空塔", "skytree", "스카이트리");

        // 江东区 (Koto) - 填海新区
        addLandmark("江东", "丰洲", "豊洲", "toyosu", "도요스", "有明", "ariake", "아리아케", "青海", "aomi", "龟户", "亀戸", "kameido");

        // 品川区 (Shinagawa)
        addLandmark("品川", "五反田", "gotanda", "고탄다", "大崎", "osaki", "오사키", "户越", "togoshi", "天王洲", "tennoz");

        // 其他热门地标兜底
        addLandmark("浦安", "迪士尼", "ディズニー", "disney", "디즈니"); // 东京迪士尼其实在千叶县浦安市
    }

    private static void addLandmark(String targetWard, String... keywords) {
        for (String kw : keywords) {
            LANDMARK_MAP.put(kw.toLowerCase(), targetWard);
        }
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

        // ==========================================
        // 1. 如果能找到邮编，先截取出邮编，通过 ZipCloud API 查所属区
        // ==========================================
        Matcher zipMatcher = Pattern.compile("(\\d{3}-?\\d{4})").matcher(address);
        if (zipMatcher.find()) {
            String zip = zipMatcher.group(1).replace("-", "");
            String zipWard = callZipCloud(zip);
            if (zipWard != null && !zipWard.isEmpty()) {
                return formatFinalResult(zipWard);
            }
        }

        // ==========================================
        // 1.5 新增：商圈与地标特征词快速扫描 (专治没有行政区划的 POI 地址)
        // ==========================================
        String addrLower = address.toLowerCase();
        for (Map.Entry<String, String> entry : LANDMARK_MAP.entrySet()) {
            if (addrLower.contains(entry.getKey())) {
                // 如果地址里包含 "긴자" (银座)，直接返回 "中央区"
                return formatFinalResult(entry.getValue() + "区");
            }
        }

        // ==========================================
        // 2. 找不到邮编的，用正则本地查询（处理多区歧义）
        // ==========================================
        // ... 接原来的第2步代码 ...

        // ==========================================
        // 2. 找不到邮编的，用正则本地查询（处理多区歧义）
        // ==========================================
        Set<String> foundWards = new HashSet<>();
        // 匹配中日文“xx区”或英文“xx-ku”
        Matcher wardMatcher = Pattern
                .compile("([\\u4e00-\\u9fa5]+?区|[A-Za-z]+-ku|[A-Za-z\\s]+City)", Pattern.CASE_INSENSITIVE)
                .matcher(address);
        while (wardMatcher.find()) {
            String w = wardMatcher.group(1);
            String normalized = normalizeWard(w); // 归一化，比如把 shibuya-ku 和 渋谷区 都统一成标准格式
            if (normalized != null && !normalized.isEmpty()) {
                foundWards.add(normalized);
            }
        }

        // 如果本地只精确匹配到了一个区，直接返回
        if (foundWards.size() == 1) {
            return formatFinalResult(foundWards.iterator().next());
        }
        // 注意：如果 foundWards.size() > 1（存在多个区），不确认，直接走第3步兜底
        // 如果 size() == 0，没找到，也走第3步兜底

        // ==========================================
        // 3. 走原有的 requestNominatim 接口兜底
        // ==========================================
        try {
            Thread.sleep(1000); // 保护 IP
        } catch (InterruptedException e) {
        }

        String nominatimResult = getFromNominatimLogic(address);
        if (nominatimResult != null && !nominatimResult.isEmpty()) {
            return formatFinalResult(nominatimResult);
        }

        // ==========================================
        // 4. 怎么都找不到的，以原地址包裹【】返回
        // ==========================================
        return "【" + address + "】";
    }

    // --- 统一输出格式化模块（处理要求5：过滤“区”，保留港区、北区） ---
    private static String formatFinalResult(String text) {
        if (text == null || text.isEmpty())
            return "";
        // 先去重，再繁转简，最后运用正则负向断言干掉多余的“区”
        String simplified = deduplicate(convertToSimplified(text));
        return simplified.replaceAll("(?<!港|北)区", "");
    }

    // --- 归一化本地区域，确保比对去重时的准确性 ---
    private static String normalizeWard(String wardInput) {
        if (wardInput == null)
            return null;

        // 1. 统一转小写，并去掉 -ku 和 City 后缀
        String lower = wardInput.toLowerCase()
                .replace("-ku", "")
                .replaceAll("(?i)\\s*city", "") // 去掉可能存在的 City
                .trim();

        // 2. 去掉可能存在的“区”字
        String testKey = lower.replace("区", "");

        // 3. 从你的本地映射表中找标准名
        if (REGION_MAP.containsKey(testKey)) {
            return REGION_MAP.get(testKey) + "区";
        }
        return wardInput;
    }

    // --- ZipCloud 邮编查询模块 ---
    private static String callZipCloud(String zipCode) {
        try {
            URL url = new URL("https://zipcloud.ibsnet.co.jp/api/search?zipcode=" + zipCode);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                // 提取 address2 (市区町村)
                return getJsonValue(response.toString(), "address2");
            }
        } catch (Exception e) {
            // 失败则默默吃掉异常，交由下一步处理
        }
        return "";
    }

    // --- 将你原有的 API 复杂逻辑抽离成独立方法 ---
    private static String getFromNominatimLogic(String address) {
        // 1. 规整的纯中/日文快速截取（保留你原有的优秀逻辑）
        Matcher mJp = Pattern.compile("([\\u4e00-\\u9fa5]+?[省道府県县都])?([\\u4e00-\\u9fa5]+?[市区])").matcher(address);
        if (mJp.find()) {
            String prov = mJp.group(1) == null ? "" : mJp.group(1);
            String city = mJp.group(2);
            return (prov + city).replace("東京都", "东京").replace("大阪府", "大阪");
        }

        // 2. 第一波：常规格式清洗
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
            parsedResult = parseNominatimJson(requestNominatim(query1));
        }

        // 3. 第二波：X光扫描（清理噪音词汇再请求一次）
        if (parsedResult.isEmpty()) {
            String kwSource = address.replaceAll("(?i)ch[oō]me", " ");
            Matcher mKw = Pattern.compile("[A-Za-z]{3,}").matcher(kwSource);
            StringBuilder kwBuilder = new StringBuilder();
            while (mKw.find()) {
                String w = mKw.group();
                if (!w.toLowerCase().matches(
                        "hotel|inn|hostel|japan|city|ward|ku|shi|premier|the|of|and|in|at|room|floor|building|plaza|dia|daiwa|roynet|nestay|dai")) {
                    kwBuilder.append(w).append(" ");
                }
            }
            String query2 = kwBuilder.toString().trim();

            if (!query2.isEmpty() && !query2.equals(query1)) {
                parsedResult = parseNominatimJson(requestNominatim(query2));
            }
        }
        return parsedResult;
    }

    public static String convertToSimplified(String text) {
        if (text == null || text.isEmpty())
            return text;
        return ZhConverterUtil.toSimple(text);
    }

    private static String deduplicate(String text) {
        if (text == null || text.isEmpty())
            return text;
        String regex = "([\\u4e00-\\u9fa5]{2,})[\\s/]*\\1";
        String last;
        String current = text;
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
                    + "&format=json&addressdetails=1&limit=1&countrycodes=jp";
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

    // 简单的正则 JSON 取值
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
        String add1 = "東京都Chūō役所(1-chōme-4-1 江戸川区, Edogawa City, Tokyo 132-8501日本)";
        String add2 = "2-3-1 Yoyogi, Shibuya-ku, Tokyo, 151-0053 Japan,Shibuya, Tokyo, Japan(Hotel Sunroute Plaza Shinjuku)";
        String add3 = "1-1-7 Sakuragicho, Naka-Ku, Yokohama, Japan(New Otani Inn Yokohama Premium)"; // 测试港区
        String add4 = "Japan, 〒105-8563 Tokyo, Minato City, Shibakōen, 4-chōme−8−１ ザ・プリンス パークタワー東京(The Prince Park Tower Tokyo";

        System.out.println(add1 + "  ===>  " + getRegionSmart(add1));
        System.out.println(add2 + "  ===>  " + getRegionSmart(add2));
        System.out.println(add3 + "  ===>  " + getRegionSmart(add3));
        System.out.println(add4 + "  ===>  " + getRegionSmart(add4));
    }
}