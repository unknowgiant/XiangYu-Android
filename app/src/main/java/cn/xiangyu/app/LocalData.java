package cn.xiangyu.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class LocalData {
    static final class Place {
        final String city;
        final String province;
        final double lat;
        final double lon;
        final String intro;
        final List<Item> food;
        final List<Item> culture;
        final List<Item> sights;
        final List<Item> tips;
        final List<Item> hotels;

        Place(String city, String province, double lat, double lon, String intro,
              List<Item> food, List<Item> culture, List<Item> sights) {
            this(city, province, lat, lon, intro, food, culture, sights, commonTips(city), commonHotels(city));
        }

        Place(String city, String province, double lat, double lon, String intro,
              List<Item> food, List<Item> culture, List<Item> sights, List<Item> tips) {
            this(city, province, lat, lon, intro, food, culture, sights, tips, commonHotels(city));
        }

        Place(String city, String province, double lat, double lon, String intro,
              List<Item> food, List<Item> culture, List<Item> sights, List<Item> tips, List<Item> hotels) {
            this.city = city;
            this.province = province;
            this.lat = lat;
            this.lon = lon;
            this.intro = intro;
            this.food = food;
            this.culture = culture;
            this.sights = sights;
            this.tips = tips;
            this.hotels = hotels;
        }
    }

    static final class Item {
        final String id;
        final String title;
        final String subtitle;
        final String meta;
        final int color;
        final String mark;
        final double lat;
        final double lon;

        Item(String id, String title, String subtitle, String meta, int color, String mark) {
            this(id, title, subtitle, meta, color, mark, Double.NaN, Double.NaN);
        }

        Item(String id, String title, String subtitle, String meta, int color, String mark, double lat, double lon) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.meta = meta;
            this.color = color;
            this.mark = mark;
            this.lat = lat;
            this.lon = lon;
        }

        boolean hasLocation() { return !Double.isNaN(lat) && !Double.isNaN(lon); }
    }

    private static Item item(String id, String title, String subtitle, String meta, int color, String mark) {
        return new Item(id, title, subtitle, meta, color, mark);
    }

    static final List<Place> PLACES = Arrays.asList(
        new Place("上海", "上海市", 31.2304, 121.4737, "江海交汇，弄堂烟火与海派风情相映成趣",
            Arrays.asList(
                item("sh-food-1", "生煎馒头", "底脆、皮薄、汤鲜，一口是上海清晨的热气。", "经典早点 · 人均 ¥18", 0xffd66a45, "煎"),
                item("sh-food-2", "葱油拌面", "熬香的葱油裹住细面，简单却很见功夫。", "本帮风味 · 人均 ¥22", 0xffd4a340, "面"),
                item("sh-food-3", "排骨年糕", "炸排骨配软糯年糕，甜咸酱汁是老上海味。", "市井小吃 · 人均 ¥28", 0xff9e6240, "糕")),
            Arrays.asList(
                item("sh-culture-1", "石库门里弄", "中西建筑交融的居住形态，藏着邻里生活的尺度。", "城市风貌 · 建议慢行", 0xff56776c, "里"),
                item("sh-culture-2", "海派剪纸", "兼收南北技法，构图细腻，题材贴近都市生活。", "非遗技艺 · 可体验", 0xffb64b3b, "艺"),
                item("sh-culture-3", "沪剧", "以吴语演唱，曲调柔和，讲述寻常人家的悲欢。", "地方戏曲 · 吴语", 0xff785c83, "曲")),
            Arrays.asList(
                item("sh-sight-1", "外滩建筑群", "沿黄浦江看万国建筑，也看浦东天际线。", "地标 · 免费开放", 0xff47748a, "江"),
                item("sh-sight-2", "豫园", "明代园林藏在老城厢，一步一景，闹中取静。", "人文园林 · 约2小时", 0xff55765b, "园"),
                item("sh-sight-3", "武康路", "梧桐树下读城市建筑，适合午后步行。", "街区漫游 · 免费", 0xff9b704d, "路"))),
        new Place("北京", "北京市", 39.9042, 116.4074, "古都中轴延伸向现代都市，四季各有讲究",
            Arrays.asList(
                item("bj-food-1", "老北京炸酱面", "筋道面条配八碟菜码，拌开就是胡同家常味。", "京味主食 · 人均 ¥32", 0xffc8623c, "酱"),
                item("bj-food-2", "豆汁焦圈", "酸香豆汁配酥脆焦圈，是地道北京人的晨间仪式。", "传统早点 · 人均 ¥12", 0xff7b7d48, "汁"),
                item("bj-food-3", "铜锅涮肉", "清水锅底涮手切羊肉，麻酱小料浓香。", "聚餐首选 · 人均 ¥110", 0xffa14d3e, "涮")),
            Arrays.asList(
                item("bj-culture-1", "胡同生活", "灰墙青瓦之间，藏着老城的邻里秩序与烟火。", "城市风貌 · 适合骑行", 0xff5d7568, "巷"),
                item("bj-culture-2", "京剧", "唱念做打讲究程式，一桌二椅演尽人间百态。", "国粹 · 可观演", 0xffa54436, "戏"),
                item("bj-culture-3", "兔儿爷", "中秋应节的泥塑彩绘，威风里带着亲切。", "民间工艺 · 非遗", 0xffbc8741, "兔")),
            Arrays.asList(
                item("bj-sight-1", "故宫博物院", "沿中轴读懂明清宫城，建筑与馆藏都值得细看。", "需预约 · 建议半天", 0xffa94a39, "宫"),
                item("bj-sight-2", "颐和园", "山水园林气象开阔，长廊与昆明湖四季皆宜。", "皇家园林 · 约4小时", 0xff477a70, "湖"),
                item("bj-sight-3", "天坛公园", "古代祭天建筑群，清晨也能遇见鲜活的市民生活。", "世界遗产 · 约3小时", 0xff476c88, "坛"))),
        new Place("成都", "四川省", 30.5728, 104.0668, "茶馆、川味与慢生活，共同组成安逸成都",
            Arrays.asList(
                item("cd-food-1", "钟水饺", "薄皮水饺淋红油甜酱，甜辣交织、鲜香细腻。", "成都名小吃 · 人均 ¥16", 0xffc84d34, "饺"),
                item("cd-food-2", "担担面", "芽菜、肉臊与红油铺底，小碗却层次十足。", "街头小面 · 人均 ¥14", 0xffc66d32, "担"),
                item("cd-food-3", "串串香", "荤素串进红汤，蘸干碟，是夜晚的热闹滋味。", "夜宵 · 人均 ¥65", 0xffb74332, "串")),
            Arrays.asList(
                item("cd-culture-1", "盖碗茶", "茶馆里一坐半日，是成都人与时间相处的方式。", "生活风俗 · 可体验", 0xff4f7965, "茶"),
                item("cd-culture-2", "川剧变脸", "锣鼓声中脸谱瞬息变换，技法神秘而利落。", "传统戏曲 · 非遗", 0xffa33f34, "脸"),
                item("cd-culture-3", "蜀锦", "经纬间织出繁复纹样，色彩浓丽，技艺传承千年。", "传统织造 · 非遗", 0xff765886, "锦")),
            Arrays.asList(
                item("cd-sight-1", "武侯祠", "红墙竹影里读三国历史，紧邻锦里古街。", "人文古迹 · 约2小时", 0xffa34737, "蜀"),
                item("cd-sight-2", "青城山", "曲径幽深、林木苍翠，是成都周边清凉去处。", "自然人文 · 建议1天", 0xff467461, "山"),
                item("cd-sight-3", "成都熊猫基地", "近距离观察大熊猫，清晨活跃度通常更高。", "需预约 · 建议早到", 0xff73824c, "猫"))),
        new Place("广州", "广东省", 23.1291, 113.2644, "骑楼、早茶和珠江水，养出务实鲜活的岭南气质",
            Arrays.asList(
                item("gz-food-1", "虾饺", "澄面皮透亮，整虾爽弹，是广式早茶的招牌。", "早茶点心 · 人均 ¥25", 0xffd0694f, "虾"),
                item("gz-food-2", "肠粉", "米浆蒸成薄皮，包肉蛋鲜虾，酱油点出米香。", "街坊早餐 · 人均 ¥15", 0xffb98b54, "粉"),
                item("gz-food-3", "艇仔粥", "绵滑粥底汇入鱼片、花生和油条，鲜香温润。", "西关小吃 · 人均 ¥20", 0xffa66c3f, "粥")),
            Arrays.asList(
                item("gz-culture-1", "饮早茶", "一盅两件不只是吃饭，更是从容的社交日常。", "生活风俗 · 宜慢品", 0xff56745f, "茶"),
                item("gz-culture-2", "粤剧", "红船余韵流传至今，唱腔婉转，行当鲜明。", "岭南戏曲 · 非遗", 0xffa34539, "粤"),
                item("gz-culture-3", "骑楼街", "连续廊道遮阳避雨，建筑回应了岭南气候。", "城市风貌 · 适合步行", 0xff7c7458, "楼")),
            Arrays.asList(
                item("gz-sight-1", "陈家祠", "木雕、砖雕、石雕满布其间，是岭南工艺大观。", "人文建筑 · 约2小时", 0xff8d553f, "祠"),
                item("gz-sight-2", "沙面岛", "欧陆建筑与古树相伴，适合傍晚沿江漫步。", "历史街区 · 免费", 0xff537586, "岛"),
                item("gz-sight-3", "白云山", "登高俯瞰城市，林间步道适合半日轻徒步。", "城市山林 · 约4小时", 0xff4c765c, "云"))),
        new Place("西安", "陕西省", 34.3416, 108.9398, "城墙围住十三朝故事，也围不住碳水香气",
            Arrays.asList(
                item("xa-food-1", "肉夹馍", "腊汁肉酥烂浓香，白吉馍外脆内软。", "经典小吃 · 人均 ¥18", 0xffa95b35, "馍"),
                item("xa-food-2", "牛羊肉泡馍", "自己掰馍再煮，汤浓肉烂，吃法很有仪式感。", "西北主食 · 人均 ¥38", 0xff947149, "泡"),
                item("xa-food-3", "凉皮", "米皮爽滑，辣子香而不燥，夏日尤其清爽。", "街头小吃 · 人均 ¥12", 0xffc16a36, "皮")),
            Arrays.asList(
                item("xa-culture-1", "秦腔", "高亢激越，梆子铿锵，唱的是黄土地上的直爽。", "地方戏曲 · 非遗", 0xff9c4336, "秦"),
                item("xa-culture-2", "城墙灯会", "传统灯彩沿古城墙展开，年节氛围尤为浓厚。", "节庆风俗 · 新春", 0xffb27a35, "灯"),
                item("xa-culture-3", "皮影戏", "灯影里雕花皮人翻飞，唱腔与操偶紧密相合。", "民间艺术 · 非遗", 0xff765a50, "影")),
            Arrays.asList(
                item("xa-sight-1", "秦始皇帝陵博物院", "从兵马俑军阵窥见秦代制度与工艺。", "世界遗产 · 建议半天", 0xff876044, "秦"),
                item("xa-sight-2", "西安城墙", "骑行一周看古城格局，日落时分格外舒展。", "古城地标 · 约3小时", 0xff675f54, "城"),
                item("xa-sight-3", "陕西历史博物馆", "从史前到盛唐，馆藏串起中华文明脉络。", "需预约 · 建议半天", 0xff8b493d, "唐"))),
        new Place("杭州", "浙江省", 30.2741, 120.1551, "湖山入城，茶香与江南日常都温润从容",
            Arrays.asList(
                item("hz-food-1", "片儿川", "雪菜、笋片和肉片浇头，鲜爽是杭州人的乡愁。", "杭式汤面 · 人均 ¥25", 0xffb66a3e, "川"),
                item("hz-food-2", "葱包桧", "油条与葱裹进薄饼压烤，甜面酱增添咸甜。", "街头小吃 · 人均 ¥10", 0xffa87c3f, "葱"),
                item("hz-food-3", "定胜糕", "松软米糕带豆沙馅，粉红模样寓意吉祥。", "传统糕点 · 人均 ¥12", 0xffc87868, "糕")),
            Arrays.asList(
                item("hz-culture-1", "龙井问茶", "春日采茶、炒茶、品茶，体会一叶中的风土。", "茶俗 · 春季最佳", 0xff4b7755, "茶"),
                item("hz-culture-2", "杭绣", "针法细密、设色雅致，常以西湖风景入绣。", "传统技艺 · 非遗", 0xff6a687d, "绣"),
                item("hz-culture-3", "南宋雅生活", "焚香、点茶、插花与挂画，映照宋韵审美。", "人文体验 · 宋韵", 0xff867050, "宋")),
            Arrays.asList(
                item("hz-sight-1", "西湖", "步行、骑行或泛舟，各自看到不同的湖山层次。", "世界遗产 · 免费", 0xff4d7880, "湖"),
                item("hz-sight-2", "灵隐寺", "古刹隐于飞来峰下，造像与山林相得益彰。", "人文古迹 · 约3小时", 0xff57725b, "隐"),
                item("hz-sight-3", "西溪湿地", "水道纵横、芦苇摇曳，可乘摇橹船慢游。", "自然湿地 · 约4小时", 0xff4c7468, "溪")))
    );

    static Place nearest(double lat, double lon) {
        Place best = PLACES.get(0);
        double distance = Double.MAX_VALUE;
        for (Place place : PLACES) {
            double d = Math.pow(lat - place.lat, 2) + Math.pow((lon - place.lon) * Math.cos(Math.toRadians(lat)), 2);
            if (d < distance) {
                distance = d;
                best = place;
            }
        }
        return best;
    }

    static List<Item> items(Place place, int tab) {
        if (tab == 1) return place.culture;
        if (tab == 2) return place.sights;
        if (tab == 3) return place.tips;
        if (tab == 4) return place.hotels;
        return place.food;
    }

    static Place forCity(CityRepository.City city) {
        for (Place place : PLACES) {
            if (place.city.equals(city.name)) {
                RegionProfile profile = profile(city.province);
                return new Place(place.city, city.province, city.lat, city.lon, place.intro,
                    expandedFood(place.food, city, profile), expandedCulture(place.culture, city, profile),
                    expandedSights(place.sights, city, profile),
                    place.tips, commonHotels(city.name));
            }
        }
        RegionProfile profile = profile(city.province);
        CityContentData.CityContent content = CityContentData.forName(city.name);
        if (content == null) {
            throw new IllegalStateException("Missing generated content for city " + city.code);
        }
        return new Place(city.name, city.province, city.lat, city.lon,
            "已收录 " + city.officialName + " 的地方风物",
            expandedFood(toItems(city, "food", content.food, 0xffc4633f, "食"), city, profile),
            expandedCulture(toItems(city, "culture", content.culture, 0xff537263, "俗"), city, profile),
            expandedSights(toItems(city, "sight", content.sights, 0xff47748a, "景"), city, profile),
            commonTips(city.name), commonHotels(city.name));
    }

    private static List<Item> toItems(CityRepository.City city, String category,
                                      CityContentData.Entry[] entries, int color, String mark) {
        List<Item> result = new ArrayList<>();
        for (int i = 0; i < entries.length; i++) {
            CityContentData.Entry entry = entries[i];
            result.add(item(city.code + "-" + category + "-" + (i + 1),
                entry.title, entry.subtitle, entry.meta, color, mark));
        }
        return result;
    }

    static void validateCoverage(List<CityRepository.City> cities) {
        if (cities.size() != 337) throw new IllegalStateException("City coverage must contain 337 records");
        for (CityRepository.City city : cities) {
            Place place = forCity(city);
            if (!place.city.equals(city.name) || place.food.size() < 5 || place.culture.size() < 5
                    || place.sights.size() < 6 || place.tips.size() < 3 || place.hotels.size() < 5) {
                throw new IllegalStateException("Incomplete city content: " + city.code + " " + city.name);
            }
            validateCategory(city, place.food, "food");
            validateCategory(city, place.culture, "culture");
            validateCategory(city, place.sights, "sight");
        }
    }

    private static void validateCategory(CityRepository.City city, List<Item> items, String category) {
        for (Item value : items) {
            if (!value.id.startsWith(city.code + "-") && !city.curatedContent) {
                throw new IllegalStateException("Cross-city " + category + " item: " + city.code + " / " + value.id);
            }
        }
    }

    static Place withRankedHotels(Place base, CityRepository.City city, List<String> rankedNames,
                                  java.util.Map<String, String> rankedSources) {
        if (rankedNames.isEmpty()) return base;
        List<Item> hotels = new ArrayList<>();
        int rank = 1;
        for (String name : rankedNames) {
            addUnique(hotels, item(city.code + "-hotel-rank-" + rank, name,
                "公开平台结果中的本地住宿候选。优先比较含税总价、近期住客评价、卫生、隔音、取消政策和到公共交通的真实步行距离。",
                rankedSources.getOrDefault(name, "公开平台") + "公开结果 · 当前检索第" + rank + "位",
                0xff536f78, "宿"), 5);
            rank++;
        }
        for (Item item : base.hotels) addUnique(hotels, item, 10);
        return new Place(base.city, base.province, base.lat, base.lon, base.intro,
            base.food, base.culture, base.sights, base.tips, hotels);
    }

    static Place withTravelTips(Place base, List<Item> onlineTips) {
        List<Item> tips = new ArrayList<>(onlineTips);
        for (Item item : base.tips) {
            boolean duplicate = false;
            for (Item existing : tips) if (existing.title.equals(item.title)) duplicate = true;
            if (!duplicate && tips.size() < 8) tips.add(item);
        }
        return new Place(base.city, base.province, base.lat, base.lon, base.intro,
            base.food, base.culture, base.sights, tips, base.hotels);
    }

    private static List<Item> expandedSights(List<Item> curated, CityRepository.City city, RegionProfile profile) {
        List<Item> result = new ArrayList<>(curated);
        result.add(item(city.code + "-sight-more-1", city.name + "地方博物馆", "从地方馆藏了解城市历史、物产和民俗，出发前确认预约与闭馆日。", "人文场馆 · 开放时间需复核", 0xff6b647a, "博"));
        result.add(item(city.code + "-sight-more-2", "老城与历史街区", "避开高峰慢行观察建筑与街巷生活，游览时尊重居民空间。", "城市漫游 · 建议步行", 0xff8b694a, "巷"));
        result.add(item(city.code + "-sight-more-3", profile.landscape, "结合天气和交通安排半日或一日行程，山区与远郊需预留返程时间。", "周边景观 · 行前核实", 0xff4f7562, "景"));
        result.add(item(city.code + "-sight-more-4", city.name + "亲子科普与遛娃去处", "优先查找科技馆、动物园、自然教育中心和休息设施完善的公园，提前确认预约与适龄范围。", "亲子遛娃 · 联网补充具体地点", 0xff4f7562, "亲"));
        result.add(item(city.code + "-sight-more-5", city.name + "红色记忆与纪念场馆", "结合纪念馆、革命旧址和会址了解地方历史，团队参观前确认讲解和预约时段。", "红色学习 · 联网补充具体地点", 0xffa34c3a, "红"));
        result.add(item(city.code + "-sight-more-6", city.name + "小众自然景观", "寻找湿地、森林、峡谷、地质遗迹和自然保护地，提前核实天气、道路、补给和返程。", "纯自然景观 · 联网补充具体地点", 0xff4f7562, "野"));
        return result;
    }

    private static List<Item> expandedFood(List<Item> curated, CityRepository.City city, RegionProfile profile) {
        List<Item> result = new ArrayList<>(curated);
        addUnique(result, item(city.code + "-food-more-1", profile.food1,
            "这类风味通常与当地物产和居民饮食习惯有关，建议从街坊常去的小店少量品尝。",
            "地域风味 · 到店核实", 0xffc4633f, "味"), 6);
        addUnique(result, item(city.code + "-food-more-2", profile.food2,
            "不同街区和县区的做法、调味与配料会有差异，可比较两家以上再判断是否合口味。",
            "地方小吃 · 少量多尝", 0xffb98942, "食"), 6);
        addUnique(result, item(city.code + "-food-more-3", "本地传统早点",
            "清晨到居民区或菜市场周边更容易找到日常早餐，热门品类可能较早售罄。",
            "晨间风味 · 留意营业时间", 0xffd17a42, "早"), 6);
        return result;
    }

    private static List<Item> expandedCulture(List<Item> curated, CityRepository.City city, RegionProfile profile) {
        List<Item> result = new ArrayList<>(curated);
        addUnique(result, item(city.code + "-culture-more-1", profile.culture1,
            "当地礼俗与历史、气候和物产相互影响，体验前先了解称呼、禁忌与拍摄规则。",
            "生活礼俗 · 尊重当地习惯", 0xff537263, "俗"), 6);
        addUnique(result, item(city.code + "-culture-more-2", profile.culture2,
            "可通过地方博物馆、非遗馆或正规演出了解其历史背景、技艺特点与传承现状。",
            "传统文化 · 尊重礼俗", 0xff8a5f76, "艺"), 6);
        addUnique(result, item(city.code + "-culture-more-3", "地方节庆与市集",
            "节庆日期和活动范围可能临时调整，参加时遵守现场秩序并尊重居民与仪式空间。",
            "民俗活动 · 提前核实日期", 0xffa45c4f, "节"), 6);
        return result;
    }

    private static void addUnique(List<Item> target, Item candidate, int max) {
        if (target.size() >= max) return;
        for (Item item : target) if (item.title.equals(candidate.title)) return;
        target.add(candidate);
    }

    private static List<Item> commonTips(String city) {
        String prefix = city == null ? "trip" : city;
        String name = city == null ? "当地" : city;
        return Arrays.asList(
            item(prefix + "-tip-1", name + "出租车与包车核查", "优先使用正规平台或站点车辆，上车前确认计价方式、路线、过路停车费用；议价包车应写明车型、时长、景点和退改条件。", "风险核查项 · 不乘无证揽客车辆", 0xffa34c3a, "车"),
            item(prefix + "-tip-2", name + "低价团与强制购物", "明显低于交通和住宿成本的团费需谨慎，报名合同应写明购物次数、自费项目和停留时长，遇强制消费保留合同及付款凭证。", "风险核查项 · 核对合同与旅行社资质", 0xffa06a39, "购"),
            item(prefix + "-tip-3", name + "门票与二次收费", "购买前区分大门票、交通票、园中园、演出和体验项目，确认是否必须组合购买，避免在非官方渠道重复购票。", "风险核查项 · 以官方票种说明为准", 0xff9a6842, "票"),
            item(prefix + "-tip-4", name + "排队与现场管理", "节假日重点核对预约时段、入口变化、接驳排队、停车容量和临时限流；现场拥挤或秩序异常时优先保证安全。", "风险核查项 · 预留等候与疏散时间", 0xff786f58, "序"),
            item(prefix + "-tip-5", name + "餐饮住宿消费", "点餐和入住前确认总价、计价单位、押金、加床早餐及退订条款，不接受未明码标价的临时加项，保留订单和支付记录。", "风险核查项 · 先确认总价与退订", 0xff8f5f52, "价"),
            item(prefix + "-tip-6", name + "远郊返程与接驳", "远郊景点需核对末班车、接驳预约和夜间叫车条件，不只看地图直线距离；返程方案至少保留一种备用方式。", "风险核查项 · 提前确认末班时间", 0xff557181, "行"));
    }

    private static List<Item> commonHotels(String city) {
        String prefix = city == null ? "trip" : city;
        return Arrays.asList(
            item(prefix + "-hotel-1", "市中心住宿区域", "餐饮和公共交通通常更集中，适合第一次到访或停留时间较短的行程。", "住宿参考 · 核实价格与取消政策", 0xff536f78, "宿"),
            item(prefix + "-hotel-2", "交通枢纽周边", "早班或晚班出行更方便，但预订前应查看隔音、步行路线和夜间交通。", "住宿参考 · 核实距离与营业状态", 0xff68765a, "站"),
            item(prefix + "-hotel-3", "青年旅舍与经济型酒店", "适合控制预算或短住，预订前重点查看独立卫浴、隔音、行李寄存和夜间入住条件。", "平价优先 · 查看近期住客评价", 0xff52786f, "青"),
            item(prefix + "-hotel-4", "居民区平价住宿", "生活配套通常更便利，餐饮价格相对日常，但要核实距地铁公交的真实步行距离。", "平价参考 · 核实交通距离", 0xff6f7656, "民"),
            item(prefix + "-hotel-5", "景区周边住宿", "可减少往返时间，旺季价格和空房变化较快，先确认接驳与退订条件。", "住宿参考 · 价格空房需复核", 0xff87624f, "游"));
    }

    private static final class RegionProfile {
        final String food1, food2, culture1, culture2, landscape;
        RegionProfile(String food1, String food2, String culture1, String culture2, String landscape) {
            this.food1 = food1; this.food2 = food2; this.culture1 = culture1; this.culture2 = culture2; this.landscape = landscape;
        }
    }

    private static RegionProfile profile(String province) {
        if (province.contains("广东")) return new RegionProfile("广府点心与早茶", "烧味与糖水", "岭南饮食礼俗", "粤剧与醒狮", "骑楼、海岸与岭南山水");
        if (province.contains("广西")) return new RegionProfile("米粉风味", "酸嘢与糯食", "壮乡歌圩", "多民族节庆", "喀斯特山水与边关风貌");
        if (province.contains("四川") || province.contains("重庆")) return new RegionProfile("川味小吃", "面食与火锅", "茶馆生活", "川剧与民间技艺", "巴蜀山水与古镇");
        if (province.contains("云南")) return new RegionProfile("米线与饵块", "菌菇与鲜花食材", "多民族节庆", "茶马古道文化", "高原湖泊、雪山与古城");
        if (province.contains("贵州")) return new RegionProfile("酸汤风味", "糯米与豆制小吃", "苗侗礼俗", "银饰、蜡染与歌舞", "瀑布、峰林与传统村寨");
        if (province.contains("陕西")) return new RegionProfile("面食与馍", "凉皮与泡馍", "秦地民俗", "秦腔与皮影", "古都遗存与黄土山川");
        if (province.contains("甘肃") || province.contains("宁夏") || province.contains("青海")) return new RegionProfile("牛羊肉与面食", "酿皮与地方茶饮", "西北商旅风俗", "多民族音乐与手工艺", "河西走廊、高原与荒漠景观");
        if (province.contains("新疆")) return new RegionProfile("馕、抓饭与烤肉", "瓜果与奶制品", "绿洲集市生活", "多民族歌舞与礼俗", "天山、草原、沙漠与古道");
        if (province.contains("西藏")) return new RegionProfile("糌粑与藏面", "酥油茶与牦牛肉", "高原生活礼俗", "藏族建筑与艺术", "雪山、湖泊与高原河谷");
        if (province.contains("内蒙古")) return new RegionProfile("奶食与手把肉", "烧麦与面食", "草原待客礼俗", "长调、马头琴与那达慕", "草原、森林与沙地");
        if (province.contains("黑龙江") || province.contains("吉林") || province.contains("辽宁")) return new RegionProfile("东北炖菜", "烧烤、饺子与粘食", "东北市井生活", "二人转与冰雪民俗", "森林、边境、海岸与冰雪景观");
        if (province.contains("福建")) return new RegionProfile("海鲜与汤粉", "茶点与地方糕饼", "闽南与客家礼俗", "南音、木偶与土楼文化", "海岛、山海古城与土楼");
        if (province.contains("海南")) return new RegionProfile("海鲜与鸡饭", "清补凉与热带水果", "琼岛生活风俗", "黎苗织锦与节庆", "热带海岸、雨林与火山");
        if (province.contains("浙江") || province.contains("江苏") || province.contains("上海")) return new RegionProfile("江南面点", "时令河鲜与糕团", "水乡生活与茶俗", "评弹、昆曲与传统工艺", "古镇、园林、湖山与海岛");
        if (province.contains("安徽") || province.contains("江西")) return new RegionProfile("米粉与地方菜", "米糕与山野食材", "宗族村落礼俗", "徽派、赣派建筑与戏曲", "山岳、古村与江河湖泊");
        if (province.contains("山东")) return new RegionProfile("面食与煎饼", "海鲜与鲁菜", "齐鲁待客礼俗", "曲艺与传统手工", "海岸、泉水与名山古城");
        if (province.contains("河南") || province.contains("河北") || province.contains("山西")) return new RegionProfile("汤食与面食", "烧饼、饸饹与地方小吃", "中原庙会与市集", "戏曲、武术与古建", "古都、太行与黄河风光");
        if (province.contains("湖北") || province.contains("湖南")) return new RegionProfile("米粉与辣味小吃", "江湖鲜味与腊味", "荆楚湖湘生活", "地方戏曲与非遗工艺", "江河湖泊、峡谷与山林");
        if (province.contains("北京") || province.contains("天津")) return new RegionProfile("京津面点", "酱卤与传统早点", "胡同街巷生活", "曲艺、戏曲与老字号", "古都建筑与城市河湖");
        return new RegionProfile("地方传统小吃", "本地面点与家常菜", "当地生活礼俗", "传统技艺与节庆", "城市人文与周边自然景观");
    }

    static List<Item> allItems() {
        List<Item> result = new ArrayList<>();
        for (Place p : PLACES) {
            result.addAll(p.food);
            result.addAll(p.culture);
            result.addAll(p.sights);
            result.addAll(p.tips);
            result.addAll(p.hotels);
        }
        return result;
    }

    private LocalData() {}
}
