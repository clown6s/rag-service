package com.zzp.rag.web.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 评测集存储：本地 JSON 文件（data/eval-sets/{id}.json），
 * 轻量运营闭环，无需建表。格式：
 * {"id","name","createdAt","items":[{"question","groundTruth"}]}
 */
@Slf4j
@Component
public class EvalSetStore {

    public record EvalItem(String question, String groundTruth) {}

    public record EvalSet(String id, String name, String createdAt, List<EvalItem> items) {}

    public record EvalSetView(String id, String name, int itemCount, String createdAt) {}

    private final ObjectMapper objectMapper;
    private final Path dir = Path.of("data", "eval-sets");

    public EvalSetStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 列出全部评测集（按创建时间倒序） */
    public List<EvalSetView> list() {
        try {
            if (!Files.isDirectory(dir)) {
                return List.of();
            }
            List<EvalSet> sets = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                for (Path p : stream.filter(f -> f.toString().endsWith(".json")).toList()) {
                    EvalSet s = objectMapper.readValue(Files.readString(p), EvalSet.class);
                    sets.add(s);
                }
            }
            return sets.stream()
                    .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                    .map(s -> new EvalSetView(s.id(), s.name(), s.items().size(), s.createdAt()))
                    .toList();
        } catch (Exception e) {
            log.warn("[评测集] 列表读取失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 创建评测集（items 以换行分隔的"问题|参考答案"文本行解析，见 parseItems） */
    public EvalSet create(String name, List<EvalItem> items) throws Exception {
        Files.createDirectories(dir);
        String id = UUID.randomUUID().toString().substring(0, 8);
        EvalSet set = new EvalSet(id, name == null || name.isBlank() ? "未命名评测集" : name,
                LocalDateTime.now().withNano(0).toString(), items);
        Files.writeString(dir.resolve(id + ".json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(set));
        return set;
    }

    /** 按 id 读取评测集；不存在返回 null */
    public EvalSet get(String id) {
        try {
            Path p = dir.resolve(id + ".json");
            return Files.exists(p) ? objectMapper.readValue(Files.readString(p), EvalSet.class) : null;
        } catch (Exception e) {
            log.warn("[评测集] 读取失败 id={}: {}", id, e.getMessage());
            return null;
        }
    }

    /** 删除评测集 */
    public boolean delete(String id) {
        try {
            return Files.deleteIfExists(dir.resolve(id + ".json"));
        } catch (Exception e) {
            log.warn("[评测集] 删除失败 id={}: {}", id, e.getMessage());
            return false;
        }
    }

    /** 文本解析："问题|参考答案"每行一条（| 分隔；无参考答案时只有问题） */
    public static List<EvalItem> parseItems(String text) {
        List<EvalItem> items = new ArrayList<>();
        for (String line : text.split("\n")) {
            String l = line.strip();
            if (l.isBlank() || l.startsWith("#")) {
                continue;
            }
            int idx = l.indexOf('|');
            if (idx > 0) {
                items.add(new EvalItem(l.substring(0, idx).strip(), l.substring(idx + 1).strip()));
            } else {
                items.add(new EvalItem(l, ""));
            }
        }
        return items;
    }

}
