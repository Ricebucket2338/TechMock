package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 面试考察方向与考点配置
 * 9 大方向，每个方向预定义 6 个核心考点（覆盖 2026 年主流技术栈）
 */
@Component
public class InterviewConfig {

    /** 最大考察方向数（无简历技术面选 6 个方向） */
    public static final int MAX_DIRECTIONS = 6;

    /** 每个方向最多问的考点数 */
    public static final int MAX_TOPICS_PER_DIRECTION = 2;

    /** 每个考点最多问的次数 */
    public static final int MAX_QUESTIONS_PER_TOPIC = 3;

    /** 八股文阶段最多选的方向数 */
    public static final int BASIC_MAX_DIRECTIONS = 5;

    /** 每个方向选几个考点 */
    public static final int DIRECTION_POINTS_PER_DIR = 2;

    /** 所有技术方向 */
    private static final List<TechDirection> ALL_DIRECTIONS = List.of(
            new TechDirection("Java基础", List.of("集合框架", "泛型与类型擦除", "异常体系", "String机制", "反射与注解", "IO/NIO模型")),
            new TechDirection("并发编程JUC", List.of("线程池参数与原理", "锁机制(synchronized/ReentrantLock)", "volatile与CAS", "并发容器(ConcurrentHashMap)", "CompletableFuture", "线程安全与可见性")),
            new TechDirection("JVM", List.of("JVM内存模型(JMM)", "GC算法与垃圾收集器", "类加载机制", "OOM排查与调优", "字节码与执行引擎", "JIT编译原理")),
            new TechDirection("MySQL", List.of("索引原理(B+树)", "事务隔离级别与MVCC", "SQL优化与执行计划", "锁机制(行锁/表锁/间隙锁)", "主从复制与高可用", "分库分表方案")),
            new TechDirection("Spring", List.of("IoC原理与Bean生命周期", "AOP原理与应用场景", "Spring事务管理", "Spring Boot自动配置", "Spring MVC请求流程", "循环依赖解决")),
            new TechDirection("Redis", List.of("数据类型与底层结构", "持久化机制(RDB/AOF)", "缓存穿透/击穿/雪崩", "集群模式(主从/哨兵/Cluster)", "分布式锁实现", "缓存一致性方案")),
            new TechDirection("MQ消息队列", List.of("消息丢失与可靠性投递", "重复消费与幂等性", "顺序消息保证", "消息积压处理", "延迟消息与死信队列", "RocketMQ vs Kafka选型")),
            new TechDirection("分布式微服务", List.of("分布式锁(Redis/ZooKeeper)", "服务降级与熔断", "分布式事务(2PC/Saga/TCC)", "限流与降级策略", "服务注册与发现", "API网关设计")),
            new TechDirection("AI大模型", List.of(
                    "Transformer与自注意力机制",
                    "LLM微调(LoRA/QLoRA)",
                    "RAG架构与检索增强",
                    "Agent设计与工具调用",
                    "向量数据库与语义检索",
                    "Prompt工程与优化",
                    "模型部署与推理优化",
                    "多模态与大模型",
                    "模型评估与benchmark",
                    "Prompt安全与注入防护",
                    "大模型微调数据准备",
                    "Agent框架设计模式"))
    );

    public static class TechDirection {
        public final String name;
        public final List<String> topics;

        public TechDirection(String name, List<String> topics) {
            this.name = name;
            this.topics = topics;
        }
    }

    /**
     * 随机选 n 个方向，每个方向内的考点打乱
     */
    public List<TechDirection> selectRandomDirections(int count) {
        return selectRandomDirections(count, false);
    }

    /**
     * 随机选 n 个方向，每个方向内的考点打乱
     * @param requireAI 是否强制包含 AI大模型 方向
     */
    public List<TechDirection> selectRandomDirections(int count, boolean requireAI) {
        List<TechDirection> nonAI = ALL_DIRECTIONS.stream()
                .filter(d -> !d.name.equals("AI大模型"))
                .collect(java.util.stream.Collectors.toList());
        Collections.shuffle(nonAI);

        List<TechDirection> result = new ArrayList<>();

        // 从非AI方向中随机选
        int nonAICount = count;
        if (requireAI) {
            nonAICount = count - 1;
        }
        int available = Math.min(nonAICount, nonAI.size());
        for (int i = 0; i < available; i++) {
            TechDirection dir = nonAI.get(i);
            List<String> shuffledTopics = new ArrayList<>(dir.topics);
            Collections.shuffle(shuffledTopics);
            result.add(new TechDirection(dir.name, shuffledTopics));
        }

        // AI大模型随机插入到结果中的任意位置
        if (requireAI) {
            TechDirection aiDir = ALL_DIRECTIONS.stream()
                    .filter(d -> d.name.equals("AI大模型"))
                    .findFirst().orElseThrow();
            List<String> shuffledAiTopics = new ArrayList<>(aiDir.topics);
            Collections.shuffle(shuffledAiTopics);
            int insertPos = new Random().nextInt(result.size() + 1);
            result.add(insertPos, new TechDirection(aiDir.name, shuffledAiTopics));
        }

        return result;
    }

    /**
     * 构建全局考点队列：将选中的方向按轮询方式排列，确保每个方向都覆盖
     *
     * 排列策略：
     * 第一轮：方向1-考点A, 方向2-考点A, ..., 方向6-考点A
     * 第二轮：方向1-考点B, 方向2-考点B, ..., 方向6-考点B
     * 第三轮：追问轮（针对答得好的考点）
     *
     * 返回 JSON 数组格式：[{"direction":"xx","topic":"xx","asked":0}, ...]
     */
    @SuppressWarnings("unchecked")
    public String buildGlobalTopicQueue(List<TechDirection> directions) {
        List<Map<String, Object>> queue = new ArrayList<>();

        // 每个方向取前 MAX_TOPICS_PER_DIRECTION 个考点
        // 按轮次排列：第一轮所有方向各1个考点，第二轮所有方向各1个考点
        for (int topicIdx = 0; topicIdx < MAX_TOPICS_PER_DIRECTION; topicIdx++) {
            for (TechDirection dir : directions) {
                if (topicIdx < dir.topics.size()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("direction", dir.name);
                    item.put("topic", dir.topics.get(topicIdx));
                    item.put("asked", 0);
                    queue.add(item);
                }
            }
        }

        try {
            return new ObjectMapper().writeValueAsString(queue);
        } catch (Exception e) {
            return "[]";
        }
    }

    public List<String> getAllDirectionNames() {
        return ALL_DIRECTIONS.stream().map(d -> d.name).toList();
    }

    /**
     * 构建八股文方向队列：随机选 5 个方向，每个方向取 2 个考点
     *
     * 返回 JSON: [{"direction":"Redis","points":[{"name":"持久化机制","asked":0},{"name":"缓存雪崩","asked":0}]}, ...]
     */
    public String buildBasicDirectionQueue(List<TechDirection> directions) {
        List<Map<String, Object>> queue = new ArrayList<>();

        for (TechDirection dir : directions) {
            Map<String, Object> dirEntry = new LinkedHashMap<>();
            dirEntry.put("direction", dir.name);

            List<Map<String, Object>> points = new ArrayList<>();
            int pointCount = Math.min(DIRECTION_POINTS_PER_DIR, dir.topics.size());
            for (int i = 0; i < pointCount; i++) {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("name", dir.topics.get(i));
                point.put("asked", 0);
                points.add(point);
            }
            dirEntry.put("points", points);
            queue.add(dirEntry);
        }

        try {
            return new ObjectMapper().writeValueAsString(queue);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * 预设场景题池，随机生成 3-4 道场景题
     */
    private static final List<String> SCENARIO_POOL = List.of(
            "线上服务突然响应变慢，CPU 飙升到 90%，你会怎么排查？请说说你的思路和会用到的工具。",
            "你在团队中推动了一个技术方案，但其他同事有不同意见，你会怎么处理这种分歧？",
            "产品经理提了一个需求，你觉得技术上不可行或者成本太高，你会怎么沟通？",
            "你负责的系统上线后出现了严重 Bug，影响了线上用户，你会怎么处理？",
            "如果你要设计一个短链生成系统，你会怎么设计？考虑存储、性能、高可用等方面。",
            "你正在做一个项目，但中途发现技术选型有问题，继续下去风险很大，你会怎么办？",
            "你的Leader给你安排了一个你从未接触过的技术任务，你会怎么开展？",
            "线上 Redis 缓存和数据库数据不一致了，你怎么定位原因并修复？",
            "一个接口平时响应 50ms，大促期间突然变成 2s，可能的原因有哪些？你怎么排查？",
            "你在之前的项目中遇到过什么技术挑战？是怎么解决的？请举一个具体的例子。",
            "如果让你从零开始搭建一个微服务项目，你会考虑哪些方面？",
            "你们团队要引入一个新技术（比如从 MySQL 迁移到 TiDB），你会怎么推进？"
    );

    public String generateScenarioQuestions() {
        List<String> pool = new ArrayList<>(SCENARIO_POOL);
        Collections.shuffle(pool);
        int count = 3 + new Random().nextInt(2); // 3 or 4
        count = Math.min(count, pool.size());

        List<Map<String, Object>> questions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("question", pool.get(i));
            q.put("asked", 0);
            questions.add(q);
        }

        try {
            return new ObjectMapper().writeValueAsString(questions);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * 从方向队列获取下一个有效考点（返回 {direction, pointName} 或 null）
     */
    @SuppressWarnings("unchecked")
    public static Map<String, String> getNextPointFromQueue(String directionQueueJson) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> queue = mapper.readValue(
                    directionQueueJson,
                    mapper.getTypeFactory().constructCollectionType(List.class, Map.class));

            for (Map<String, Object> dirEntry : queue) {
                String direction = (String) dirEntry.get("direction");
                List<Map<String, Object>> points = (List<Map<String, Object>>) dirEntry.get("points");
                if (points == null || points.isEmpty()) continue;

                for (Map<String, Object> point : points) {
                    int asked = ((Number) point.get("asked")).intValue();
                    if (asked < MAX_QUESTIONS_PER_TOPIC) {
                        Map<String, String> result = new LinkedHashMap<>();
                        result.put("direction", direction);
                        result.put("pointName", (String) point.get("name"));
                        return result;
                    }
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /**
     * 判断场景题队列是否已耗尽
     */
    @SuppressWarnings("unchecked")
    public static boolean isScenarioQueueExhausted(String scenarioQueueJson) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> queue = mapper.readValue(
                    scenarioQueueJson,
                    mapper.getTypeFactory().constructCollectionType(List.class, Map.class));

            for (Map<String, Object> q : queue) {
                int asked = ((Number) q.get("asked")).intValue();
                if (asked < MAX_QUESTIONS_PER_TOPIC) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 从场景题队列获取下一道未问过的场景题
     */
    @SuppressWarnings("unchecked")
    public static String getNextScenarioQuestion(String scenarioQueueJson) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> queue = mapper.readValue(
                    scenarioQueueJson,
                    mapper.getTypeFactory().constructCollectionType(List.class, Map.class));

            for (Map<String, Object> q : queue) {
                int asked = ((Number) q.get("asked")).intValue();
                if (asked < MAX_QUESTIONS_PER_TOPIC) {
                    return (String) q.get("question");
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /**
     * 更新队列中指定方向指定考点的 asked 计数，达到上限则从 JSON 数组中物理删除
     * 如果一个方向的所有考点都被删除了，该方向也一并删除
     */
    @SuppressWarnings("unchecked")
    public static String incrementPointAsked(String directionQueueJson, String direction, String pointName) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> queue = mapper.readValue(
                    directionQueueJson,
                    mapper.getTypeFactory().constructCollectionType(List.class, Map.class));

            for (Map<String, Object> dirEntry : queue) {
                String dir = (String) dirEntry.get("direction");
                if (!dir.equals(direction)) continue;

                List<Map<String, Object>> points = (List<Map<String, Object>>) dirEntry.get("points");
                if (points == null) continue;

                for (Iterator<Map<String, Object>> it = points.iterator(); it.hasNext(); ) {
                    Map<String, Object> point = it.next();
                    String name = (String) point.get("name");
                    if (name != null && name.equals(pointName)) {
                        int asked = ((Number) point.get("asked")).intValue();
                        asked++;
                        if (asked >= MAX_QUESTIONS_PER_TOPIC) {
                            it.remove(); // 物理删除考点
                        } else {
                            point.put("asked", asked);
                        }
                        return mapper.writeValueAsString(queue);
                    }
                }
            }
        } catch (Exception e) {
            return directionQueueJson;
        }
        return directionQueueJson;
    }

    /**
     * 从方向队列中物理删除指定方向的指定考点（用于 KNOWLEDGE_GAP）
     * 如果删除后方向没有剩余考点，该方向也一并删除
     */
    @SuppressWarnings("unchecked")
    public static String removePoint(String directionQueueJson, String direction, String pointName) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> queue = mapper.readValue(
                    directionQueueJson,
                    mapper.getTypeFactory().constructCollectionType(List.class, Map.class));

            for (Iterator<Map<String, Object>> dirIt = queue.iterator(); dirIt.hasNext(); ) {
                Map<String, Object> dirEntry = dirIt.next();
                String dir = (String) dirEntry.get("direction");
                if (!dir.equals(direction)) continue;

                List<Map<String, Object>> points = (List<Map<String, Object>>) dirEntry.get("points");
                if (points == null) continue;

                for (Iterator<Map<String, Object>> pointIt = points.iterator(); pointIt.hasNext(); ) {
                    Map<String, Object> point = pointIt.next();
                    String name = (String) point.get("name");
                    if (name != null && name.equals(pointName)) {
                        pointIt.remove(); // 物理删除考点
                        if (points.isEmpty()) {
                            dirIt.remove(); // 方向无考点了，也删除
                        }
                        return mapper.writeValueAsString(queue);
                    }
                }
            }
        } catch (Exception e) {
            return directionQueueJson;
        }
        return directionQueueJson;
    }

    /**
     * 从方向队列中物理删除指定方向（该方向下所有考点一并删除）
     */
    @SuppressWarnings("unchecked")
    public static String removeDirection(String directionQueueJson, String direction) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> queue = mapper.readValue(
                    directionQueueJson,
                    mapper.getTypeFactory().constructCollectionType(List.class, Map.class));

            queue.removeIf(dirEntry -> direction.equals(dirEntry.get("direction")));
            return mapper.writeValueAsString(queue);
        } catch (Exception e) {
            return directionQueueJson;
        }
    }

    /**
     * 清理方向队列：移除所有没有考点的方向条目
     */
    @SuppressWarnings("unchecked")
    public static String cleanEmptyDirections(String directionQueueJson) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> queue = mapper.readValue(
                    directionQueueJson,
                    mapper.getTypeFactory().constructCollectionType(List.class, Map.class));

            queue.removeIf(dirEntry -> {
                List<?> points = (List<?>) dirEntry.get("points");
                return points == null || points.isEmpty();
            });
            return mapper.writeValueAsString(queue);
        } catch (Exception e) {
            return directionQueueJson;
        }
    }

    /**
     * 判断方向队列是否已耗尽（队列为空或所有方向都无考点）
     */
    @SuppressWarnings("unchecked")
    public static boolean isDirectionQueueExhausted(String directionQueueJson) {
        if (directionQueueJson == null || directionQueueJson.isEmpty() || "[]".equals(directionQueueJson)) {
            return true;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> queue = mapper.readValue(
                    directionQueueJson,
                    mapper.getTypeFactory().constructCollectionType(List.class, Map.class));

            for (Map<String, Object> dirEntry : queue) {
                List<Map<String, Object>> points = (List<Map<String, Object>>) dirEntry.get("points");
                if (points != null && !points.isEmpty()) {
                    for (Map<String, Object> point : points) {
                        int asked = ((Number) point.get("asked")).intValue();
                        if (asked < MAX_QUESTIONS_PER_TOPIC) {
                            return false;
                        }
                    }
                }
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 更新场景题队列中某道题的 asked 计数，返回更新后的 JSON
     */
    @SuppressWarnings("unchecked")
    public static String incrementScenarioAsked(String scenarioQueueJson, String question) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> queue = mapper.readValue(
                    scenarioQueueJson,
                    mapper.getTypeFactory().constructCollectionType(List.class, Map.class));

            for (Map<String, Object> q : queue) {
                String qText = (String) q.get("question");
                if (qText != null && qText.equals(question)) {
                    int asked = ((Number) q.get("asked")).intValue();
                    q.put("asked", asked + 1);
                    return mapper.writeValueAsString(queue);
                }
            }
        } catch (Exception e) {
            return scenarioQueueJson;
        }
        return scenarioQueueJson;
    }
}
