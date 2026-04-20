package org.example.vomnisearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.IndexOperation;
import jakarta.annotation.Resource;
import org.example.vomnisearch.po.DocumentVectorMediaPo;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.*;

@SpringBootTest
class VOmniSearchApplicationTests {

    @Resource
    private ElasticsearchClient client;

    private static final String INDEX = "vector_media_index";
    private static final int VECTOR_DIM = 1024;
    private static final int BATCH_SIZE = 5000; // 每批5000条

    @Test
    void contextLoads() throws IOException, InterruptedException {
    }

//    public void testTps() throws InterruptedException {
//        int totalRequests = 1000; // 总请求数
//        int threadCount = 50;     // 并发线程数
//        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
//        CountDownLatch latch = new CountDownLatch(totalRequests);
//        LongAdder successCount = new LongAdder();
//
//        System.out.println("🚀 压测开始，正在冲击 4060 显卡...");
//        long startTime = System.currentTimeMillis();
//
//        for (int i = 0; i < totalRequests; i++) {
//            executor.submit(() -> {
//                try {
//                    // 调用你的 service，注意压测时可以暂时关掉缓存
//                    embeddingService.getVector("这是测试文本，看看 4060 能跑多快");
//                    successCount.increment();
//                } catch (Exception e) {
//                    System.err.println("请求失败: " + e.getMessage());
//                } finally {
//                    latch.countDown();
//                }
//            });
//        }
//
//        latch.await(); // 等待所有任务完成
//        long endTime = System.currentTimeMillis();
//        executor.shutdown();
//
//        // --- 计算数据 ---
//        double totalTimeSeconds = (endTime - startTime) / 1000.0;
//        double tps = successCount.sum() / totalTimeSeconds;
//
//        System.out.println("--------------------------------------");
//        System.out.println("✅ 压测完成！");
//        System.out.println("📊 总耗时: " + totalTimeSeconds + " 秒");
//        System.out.println("📈 最终 TPS: " + String.format("%.2f", tps));
//        System.out.println("--------------------------------------");
//    }


    private void writeToEs() throws IOException {
        Random random = new Random();
        String[] authorPrefix = {
                // 创作类（30+）
                "达人", "老师", "玩家", "博主", "教练", "UP主", "大厨", "旅拍", "评测", "日常",
                "老司机", "小能手", "研究员", "体验官", "主理人", "创始人", "设计师", "工程师",
                "分析师", "顾问", "主编", "摄影师", "剪辑师", "画师", "声优", "乐手",
                "导演", "编剧", "作家", "诗人", "记者", "编辑", "主播", "主持人",
                "解说员", "配音师", "动画师", "特效师", "程序员", "架构师", "产品经理", "运营",

                // 职业身份（40+）
                "运动员", "教练", "营养师", "医生", "律师", "教授", "学霸", "极客",
                "创客", "站长", "版主", "群主", "队长", "团长", "经理", "总监",
                "总裁", "CEO", "CTO", "CFO", "创始人", "合伙人", "投资人", "创业者",
                "教师", "讲师", "培训师", "导师", "辅导员", "班主任", "校长", "园长",
                "护士", "药师", "理疗师", "牙医", "兽医", "心理咨询师", "社工", "志愿者",
                "会计", "审计", "税务师", "精算师", "理财师", "规划师", "经纪人", "代理人",

                // 兴趣/生活方式（30+）
                "吃货", "旅行家", "背包客", "探险家", "摄影师", "航拍手", "画家", "书法家",
                "音乐人", "歌手", "舞者", "演员", "模特", "时尚博主", "美妆博主", "穿搭博主",
                "健身达人", "瑜伽士", "跑者", "骑友", "钓友", "茶人", "咖啡师", "调酒师",
                "铲屎官", "猫奴", "狗爸", "鸟友", "花匠", "木工", "手作人", "收藏家",

                // 网络流行/社区角色（20+）
                "课代表", "课代表助理", "热心网友", "吃瓜群众", "围观群众", "路人甲", "键盘侠", "潜水员",
                "沙发", "板凳", "前排", "后排", "楼主", "层主", "老铁", "家人们",
                "集美", "姐妹", "兄弟", "大佬", "萌新", "小白", "菜鸟", "大神"
        };

        int batches = 100000 / BATCH_SIZE;
        for (int batch = 0; batch < batches; batch++) {
            List<BulkOperation> ops = new ArrayList<>();
            for (int i = 0; i < BATCH_SIZE; i++) {
                String id = UUID.randomUUID().toString().replace("-", "");
                String title = generateRandomTitle(random);
                String author = authorPrefix[random.nextInt(authorPrefix.length)] +
                        "_" + random.nextInt(1000);
                List<Float> embedding = new ArrayList<>();
                for (int j = 0; j < VECTOR_DIM; j++) {
                    embedding.add(random.nextFloat() * 2 - 1); // 范围 [-1, 1]
                }

                // 构建文档对象
                DocumentVectorMediaPo doc = new DocumentVectorMediaPo(id, title, author, embedding);

                IndexOperation<DocumentVectorMediaPo> indexOp = IndexOperation.of(io -> io
                        .index(INDEX)
                        .id(id)
                        .document(doc)
                );
                ops.add(BulkOperation.of(bo -> bo.index(indexOp)));
            }

            BulkRequest bulkRequest = BulkRequest.of(br -> br.operations(ops));
            client.bulk(bulkRequest);
            System.out.println("已插入 " + (batch + 1) * BATCH_SIZE + " 条");
        }
        System.out.println("插入完成，共 " + 100000 + " 条");
    }

    // 领域/话题词 (80+ 个)
    String[] fields = {
            "美食", "家常菜", "烘焙", "甜品", "小吃", "探店", "吃播", "料理", "厨房", "早餐", "午餐", "晚餐", "宵夜", "零食", "水果", "饮品", "咖啡", "奶茶", "调酒", "西餐", "日料", "韩餐", "烧烤", "火锅", "海鲜", "素食", "减脂餐", "宝宝辅食", "月子餐",
            "旅游", "旅行", "自驾游", "徒步", "登山", "露营", "民宿", "酒店", "青旅", "风景", "航拍", "日出", "日落", "海边", "沙漠", "雪山", "古镇", "城市", "小众景点", "避雷", "攻略", "签证", "穷游", "环游",
            "科技", "数码", "手机", "电脑", "笔记本", "平板", "相机", "无人机", "智能家居", "穿戴", "耳机", "音响", "投影", "显示器", "显卡", "CPU", "编程", "AI", "互联网", "软件", "APP", "评测", "开箱", "对比",
            "健身", "瑜伽", "普拉提", "跑步", "马拉松", "骑行", "游泳", "撸铁", "减脂", "增肌", "塑形", "体态", "拉伸", "康复", "饮食", "补剂", "私教", "健身房", "居家锻炼",
            "美妆", "护肤", "彩妆", "口红", "眼影", "底妆", "测评", "空瓶", "种草", "拔草", "仿妆", "变装", "穿搭", "搭配", "发型", "染发", "美甲", "香水", "医美", "美容仪",
            "游戏", "单机", "主机", "Steam", "Switch", "PS5", "网游", "手游", "原神", "王者荣耀", "LOL", "吃鸡", "我的世界", "电竞", "赛事", "实况", "攻略", "通关", "速通", "模组", "独立游戏",
            "音乐", "翻唱", "原创", "吉他", "钢琴", "小提琴", "架子鼓", "编曲", "混音", "MV", "演唱会", "音乐节", "DJ", "说唱", "民谣", "古风", "流行", "摇滚", "电子", "纯音乐",
            "舞蹈", "街舞", "爵士", "韩舞", "编舞", "中国舞", "芭蕾", "拉丁", "宅舞", "翻跳", "教学", "基本功",
            "搞笑", "段子", "整蛊", "恶搞", "吐槽", "脱口秀", "相声", "小品", "鬼畜", "沙雕", "名场面",
            "宠物", "狗", "猫", "仓鼠", "兔子", "异宠", "萌宠", "训犬", "日常", "vlog", "吃播", "戏精",
            "影视", "电影", "电视剧", "动漫", "番剧", "综艺", "纪录片", "解说", "混剪", "预告", "影评", "剧评", "盘点", "冷知识",
            "汽车", "摩托车", "提车", "试驾", "评测", "改装", "自驾", "越野", "机车", "新能源", "特斯拉", "比亚迪",
            "时尚", "走秀", "奢侈品", "包包", "球鞋", "潮牌", "穿搭", "搭配", "购物", "开箱",
            "家居", "装修", "改造", "收纳", "好物", "软装", "硬装", "家电", "租房", "买房",
            "母婴", "孕期", "育儿", "辅食", "绘本", "玩具", "早教", "亲子", "二胎", "待产包",
            "教育", "学习", "考研", "考公", "英语", "日语", "数学", "物理", "化学", "历史", "地理", "政治", "生物", "语文", "高考", "中考", "四六级", "雅思", "托福", "留学",
            "财经", "股票", "基金", "理财", "保险", "房产", "投资", "商业", "创业", "副业", "省钱", "攒钱",
            "职场", "面试", "简历", "晋升", "跳槽", "管理", "沟通", "效率", "办公", "软件", "Excel", "PPT", "Word",
            "三农", "农村", "农业", "养殖", "种植", "赶海", "捕鱼", "果园", "菜园", "乡村", "田园",
            "手工", "DIY", "折纸", "模型", "粘土", "乐高", "积木", "滴胶", "羊毛毡", "钩针", "编织", "木工"
    };

    // 形容词/修饰词 (80+ 个)
    String[] adjectives = {
            "超详细", "新手必看", "保姆级", "全网最全", "一学就会", "深度解析", "真实测评", "沉浸式", "实用", "简单易学", "高级感", "治愈系", "宝藏", "良心", "硬核", "有趣", "爆笑", "唯美", "震撼", "干货", "避坑", "省钱", "省时", "高效", "零基础", "进阶", "高阶", "大师级", "专业", "业余", "万能", "必备", "冷门", "热门", "经典", "复古", "新潮", "潮流", "小众", "大众", "性价比", "天花板", "入门", "毕业", "教科书", "翻车", "成功", "失败", "绝绝子", "yyds", "真香", "破防", "无语", "离谱", "内卷", "躺平", "凡尔赛", "种草", "拔草", "智商税", "黑科技", "未来感", "极简", "复古", "赛博", "朋克", "蒸汽波", "多巴胺", "美拉德", "格雷系", "老钱风", "松弛感", "氛围感", "少年感", "少女感", "元气", "暗黑", "清新", "甜美", "酷飒", "优雅", "知性", "可爱"
    };

    // 动作/内容类型 (60+ 个)
    String[] actions = {
            "教程", "攻略", "分享", "教学", "指南", "体验", "探店", "测评", "解说", "演示", "实操", "心得", "记录", "vlog", "打卡", "挑战", "开箱", "翻唱", "编舞", "翻跳", "试吃", "试驾", "改造", "对比", "横评", "推荐", "避雷", "踩坑", "拔草", "种草", "评测", "解析", "拆解", "维修", "制作", "DIY", "烹饪", "做法", "步骤", "流程", "技巧", "窍门", "经验", "总结", "复盘", "游记", "攻略", "路书", "清单", "合集", "排行榜", "TOP10", "必买", "必去", "必吃", "必玩", "必看", "每日", "周末", "假期", "下班后"
    };

    // 标题模板 (丰富结构，涵盖各类表达)
    String[] titleTemplates = {
            "{field}{action}，{adj} {adj}",
            "{adj}{field}{action}，{adj}合集",
            "{adj}版{field}{action}，{adj}推荐",
            "【{field}】{adj}{action}，{adj}分享",
            "{adj}{field}{action}，{adj}体验",
            "关于{field}的{adj}{action}，{adj}必看",
            "{field}{action}第{num}期，{adj}干货",
            "{field}{adj}玩法，{adj}{action}",
            "挑战{field}{action}，{adj}的一天",
            "沉浸式{field}{action}，{adj}日常",
            "{field}{action}天花板，{adj} {adj}",
            "{adj}{field}{action}，真的太{adj}了",
            "求求了，{field}真的别这样{action}",
            "我不允许还有人不知道这个{field}{action}",
            "{field}怎么{action}？{adj}教程来了",
            "关于{field}，这可能是最{adj}的{action}",
            "{num}个{adj}的{field}{action}，{adj}拿走",
            "如果你也想{action}{field}，一定要看这期视频",
            "花了{num}小时整理的{field}{action}，{adj}",
            "听劝！{field}就按这个{action}来",
            "别找了，{field}最全{action}都在这了",
            "一招教你{adj}{field}{action}",
            "答应我，{field}一定要这样{action}",
            "原来{field}可以这么{action}？{adj}了",
            "谁懂啊，这个{field}{action}真的{adj}",
            "家人们，{field}这个{action}真的{adj}",
            "被问爆了的{field}{action}，{adj}分享",
            "最近很火的{field}{action}，我也来试试",
            "花了{num}大洋买的{field}{action}，值不值？",
            "当{field}遇上{adj}，会发生什么？"
    };

    private @NotNull String generateRandomTitle(@NotNull Random random) {
        String field = fields[random.nextInt(fields.length)];
        String adj1 = adjectives[random.nextInt(adjectives.length)];
        String adj2 = adjectives[random.nextInt(adjectives.length)];
        String action = actions[random.nextInt(actions.length)];
        String template = titleTemplates[random.nextInt(titleTemplates.length)];

        return template
                .replace("{field}", field)
                .replace("{action}", action)
                .replace("{num}", String.valueOf(random.nextInt(100) + 1))
                .replace("{adj1}", adj1)
                .replace("{adj2}", adj2)
                .replace("{adj}", adj1);  // 兼容旧模板中直接写 {adj} 的情况
    }
}
