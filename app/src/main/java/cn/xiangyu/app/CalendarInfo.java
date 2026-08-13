package cn.xiangyu.app;

import android.icu.util.ChineseCalendar;

import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.Locale;

final class CalendarInfo {
    private static final String[] MONTHS = {"正月", "二月", "三月", "四月", "五月", "六月",
        "七月", "八月", "九月", "十月", "冬月", "腊月"};
    private static final String[] DAYS = {"初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"};
    private static final String[] STEMS = {"甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸"};
    private static final String[] BRANCHES = {"子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥"};
    private static final String[] ZODIAC = {"鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪"};
    private static final String[] SOLAR_TERMS = {"小寒", "大寒", "立春", "雨水", "惊蛰", "春分", "清明", "谷雨", "立夏", "小满", "芒种", "夏至",
        "小暑", "大暑", "立秋", "处暑", "白露", "秋分", "寒露", "霜降", "立冬", "小雪", "大雪", "冬至"};
    private static final int[] TERM_INFO = {0, 21208, 42467, 63836, 85337, 107014, 128867, 150921,
        173149, 195551, 218072, 240693, 263343, 285989, 308563, 331033, 353350, 375494,
        397447, 419210, 440795, 462224, 483532, 504758};

    final String lunarDate;
    final String lunarYear;
    final String festival;
    final String solarTerm;
    final String suitable;
    final String avoid;
    final String season;
    final String weekInfo;
    final String observance;

    private CalendarInfo(String lunarDate, String lunarYear, String festival, String solarTerm,
                         String suitable, String avoid, String season, String weekInfo, String observance) {
        this.lunarDate = lunarDate;
        this.lunarYear = lunarYear;
        this.festival = festival;
        this.solarTerm = solarTerm;
        this.suitable = suitable;
        this.avoid = avoid;
        this.season = season;
        this.weekInfo = weekInfo;
        this.observance = observance;
    }

    static CalendarInfo of(LocalDate date) {
        ChineseCalendar lunar = new ChineseCalendar();
        lunar.setTimeInMillis(date.atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli());
        int lunarYearNumber = lunar.get(ChineseCalendar.EXTENDED_YEAR) - 2637;
        int lunarMonth = lunar.get(ChineseCalendar.MONTH);
        int lunarDay = lunar.get(ChineseCalendar.DAY_OF_MONTH);
        boolean leap = lunar.get(ChineseCalendar.IS_LEAP_MONTH) == 1;

        String lunarDate = (leap ? "闰" : "") + MONTHS[Math.max(0, Math.min(11, lunarMonth))]
            + DAYS[Math.max(0, Math.min(29, lunarDay - 1))];
        String lunarYear = STEMS[Math.floorMod(lunarYearNumber - 4, 10)]
            + BRANCHES[Math.floorMod(lunarYearNumber - 4, 12)] + "年 · "
            + ZODIAC[Math.floorMod(lunarYearNumber - 4, 12)] + "年";
        String solarTerm = solarTerm(date);
        String festival = festival(date, lunarMonth + 1, lunarDay);
        String[] suitable = {"出行 · 会友 · 纳财 · 整理", "祭祀 · 沐浴 · 扫舍 · 读书", "祈福 · 订盟 · 出行 · 访友", "会友 · 交易 · 立券 · 洽谈", "安床 · 修造 · 纳采 · 规划"};
        String[] avoid = {"动土 · 远行 · 仓促决策", "嫁娶 · 开仓 · 熬夜", "安葬 · 破土 · 冒险", "诉讼 · 迁居 · 争执", "伐木 · 作灶 · 过劳"};
        int index = Math.floorMod((int) date.toEpochDay(), suitable.length);
        int month = date.getMonthValue();
        String season = month <= 2 || month == 12 ? "冬季" : month <= 5 ? "春季" : month <= 8 ? "夏季" : "秋季";
        int week = date.get(WeekFields.of(Locale.CHINA).weekOfWeekBasedYear());
        String weekInfo = "全年第 " + date.getDayOfYear() + " 天 · 第 " + week + " 周";
        String observance = !festival.isEmpty() && !solarTerm.isEmpty() ? festival + " · " + solarTerm
            : !festival.isEmpty() ? festival : !solarTerm.isEmpty() ? solarTerm : "平日";
        return new CalendarInfo(lunarDate, lunarYear, festival, solarTerm, suitable[index], avoid[index],
            season, weekInfo, observance);
    }

    private static String festival(LocalDate date, int lunarMonth, int lunarDay) {
        if (date.getMonthValue() == 1 && date.getDayOfMonth() == 1) return "元旦";
        if (date.getMonthValue() == 5 && date.getDayOfMonth() == 1) return "劳动节";
        if (date.getMonthValue() == 10 && date.getDayOfMonth() == 1) return "国庆节";
        if (lunarMonth == 1 && lunarDay == 1) return "春节";
        if (lunarMonth == 1 && lunarDay == 15) return "元宵节";
        if (lunarMonth == 5 && lunarDay == 5) return "端午节";
        if (lunarMonth == 8 && lunarDay == 15) return "中秋节";
        if (lunarMonth == 9 && lunarDay == 9) return "重阳节";
        return "";
    }

    private static String solarTerm(LocalDate date) {
        int year = date.getYear();
        for (int i = 0; i < 24; i++) {
            long millis = (long) (31556925974.7 * (year - 1900) + TERM_INFO[i] * 60000L);
            LocalDate term = java.time.Instant.ofEpochMilli(-2208549300000L + millis)
                .atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate();
            if (term.equals(date)) return SOLAR_TERMS[i];
        }
        return "";
    }

    private CalendarInfo() { this("", "", "", "", "", "", "", "", ""); }
}
