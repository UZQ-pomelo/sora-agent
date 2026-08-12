package com.sora.sora_agent.skill;

import com.sora.sora_agent.config.SkillProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillLoader} 单元测试 — 用临时目录 fixture YAML，离线可跑。
 *
 * <p>注意：测试 classpath 含内置技能 {@code skills/web-researcher.yaml}，
 * 因此列表断言使用 contains 而非精确尺寸。</p>
 */
class SkillLoaderTest {

    @TempDir
    Path tempDir;

    private SkillProperties props(String dir) {
        SkillProperties p = new SkillProperties();
        p.setEnabled(true);
        p.setDir(dir);
        return p;
    }

    @Test
    void loadsSkillFromDir() throws Exception {
        Files.writeString(tempDir.resolve("my-skill.yaml"), """
                name: my-skill
                description: 我的测试技能
                instruction: |
                  第一步做 A
                  第二步做 B
                tools:
                  - searchweb
                examples:
                  - "帮我做 A"
                """);
        SkillLoader loader = new SkillLoader(props(tempDir.toString()));

        assertTrue(loader.names().contains("my-skill"));
        Skill skill = loader.get("my-skill");
        assertNotNull(skill);
        assertEquals("我的测试技能", skill.getDescription());
        assertTrue(skill.getInstruction().contains("第一步做 A"));
        assertEquals(1, skill.getTools().size());
        assertEquals("searchweb", skill.getTools().get(0));
    }

    @Test
    void missingDirIsTolerated() {
        SkillLoader loader = new SkillLoader(props(tempDir.resolve("nope").toString()));
        // 目录不存在仅告警；classpath 内置技能仍加载，不抛异常
        assertNotNull(loader.list());
    }

    @Test
    void badYamlIsSkipped() throws Exception {
        Files.writeString(tempDir.resolve("bad.yaml"), "name: [unclosed\n  invalid: :::");
        Files.writeString(tempDir.resolve("good.yaml"), "name: good-skill\ninstruction: ok\n");
        SkillLoader loader = new SkillLoader(props(tempDir.toString()));
        assertTrue(loader.names().contains("good-skill"));
        assertFalse(loader.names().contains("bad"));
    }

    @Test
    void missingNameUsesFilename() throws Exception {
        Files.writeString(tempDir.resolve("no-name-skill.yaml"), "instruction: hello\n");
        SkillLoader loader = new SkillLoader(props(tempDir.toString()));
        assertTrue(loader.names().contains("no-name-skill"));
    }

    @Test
    void disabledSkillIsEmpty() {
        SkillProperties p = new SkillProperties();
        p.setEnabled(false);
        p.setDir(tempDir.toString());
        SkillLoader loader = new SkillLoader(p);
        assertTrue(loader.list().isEmpty());
    }
}
