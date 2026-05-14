package com.yiming.mcp_host.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * s05: 技能加载器。
 * 扫描 skills/ 目录下所有 SKILL.md，解析 YAML frontmatter，按名提供加载。
 */
public class SkillLoader {

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\n(.*?)\\n---\\n(.*)", Pattern.DOTALL);

    private final Map<String, Skill> skills = new LinkedHashMap<>();
    private final Path skillsDir;

    public SkillLoader(Path skillsDir) {
        this.skillsDir = skillsDir;
        loadAll();
    }

    private void loadAll() {
        if (!Files.isDirectory(skillsDir)) return;

        try (Stream<Path> stream = Files.walk(skillsDir)) {
            stream.filter(p -> p.endsWith("SKILL.md"))
                    .forEach(this::loadSkillFile);
        } catch (IOException e) {
            System.err.println("[SkillLoader] 扫描技能目录失败: " + e.getMessage());
        }
    }

    private void loadSkillFile(Path path) {
        try {
            String text = Files.readString(path);
            Matcher matcher = FRONTMATTER_PATTERN.matcher(text);

            String name = path.getParent().getFileName().toString();
            String description = "";
            String body = text.strip();

            if (matcher.matches()) {
                String frontmatter = matcher.group(1);
                body = matcher.group(2).strip();

                for (String line : frontmatter.strip().split("\n")) {
                    String[] parts = line.split(":", 2);
                    if (parts.length == 2) {
                        String key = parts[0].strip();
                        String val = parts[1].strip();
                        switch (key) {
                            case "name" -> name = val;
                            case "description" -> description = val;
                        }
                    }
                }
            }

            skills.put(name, new Skill(name, description, body));
        } catch (IOException e) {
            System.err.println("[SkillLoader] 读取 " + path + " 失败: " + e.getMessage());
        }
    }

    /**
     * 列出所有可用技能描述。
     */
    public String descriptions() {
        if (skills.isEmpty()) return "(no skills)";
        StringBuilder sb = new StringBuilder();
        for (Skill s : skills.values()) {
            sb.append("  - ").append(s.name);
            if (!s.description.isEmpty()) {
                sb.append(": ").append(s.description);
            }
            sb.append("\n");
        }
        return sb.toString().strip();
    }

    /**
     * 按名加载技能内容。
     */
    public String load(String name) {
        Skill s = skills.get(name);
        if (s == null) {
            return "Error: Unknown skill '" + name + "'."
                    + " Available: " + String.join(", ", skills.keySet());
        }
        return "<skill name=\"" + name + "\">\n" + s.body + "\n</skill>";
    }

    public boolean isEmpty() { return skills.isEmpty(); }

    private record Skill(String name, String description, String body) {}
}
