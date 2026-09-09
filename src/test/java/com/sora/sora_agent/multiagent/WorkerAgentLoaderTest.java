package com.sora.sora_agent.multiagent;

import com.sora.sora_agent.config.WorkerAgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkerAgentLoader} 单元测试 — 用临时目录 fixture YAML，离线可跑。
 *
 * <p>注意：测试 classpath 含内置专家 {@code agents/*.yaml}，列表断言使用 contains。</p>
 */
class WorkerAgentLoaderTest {

    @TempDir
    Path tempDir;

    private WorkerAgentProperties props(String dir) {
        WorkerAgentProperties p = new WorkerAgentProperties();
        p.setEnabled(true);
        p.setDir(dir);
        return p;
    }

    @Test
    void loadsAgentFromDir() throws Exception {
        Files.writeString(tempDir.resolve("expert.yaml"), """
                name: expert
                description: 测试专家
                role: |
                  你是测试专家，做 A。
                tools:
                  - searchweb
                """);
        WorkerAgentLoader loader = new WorkerAgentLoader(props(tempDir.toString()));

        assertTrue(loader.names().contains("expert"));
        WorkerAgent agent = loader.get("expert");
        assertNotNull(agent);
        assertEquals("测试专家", agent.getDescription());
        assertTrue(agent.getRole().contains("测试专家"));
        assertEquals(1, agent.getTools().size());
        assertEquals("searchweb", agent.getTools().get(0));
    }

    @Test
    void missingDirIsTolerated() {
        WorkerAgentLoader loader = new WorkerAgentLoader(props(tempDir.resolve("nope").toString()));
        assertNotNull(loader.list());
    }

    @Test
    void badYamlIsSkipped() throws Exception {
        Files.writeString(tempDir.resolve("bad.yaml"), "name: [unclosed\n  invalid: :::");
        Files.writeString(tempDir.resolve("good.yaml"), "name: good-agent\ndescription: ok\nrole: 你好\n");
        WorkerAgentLoader loader = new WorkerAgentLoader(props(tempDir.toString()));
        assertTrue(loader.names().contains("good-agent"));
        assertFalse(loader.names().contains("bad"));
    }

    @Test
    void missingRoleIsSkipped() throws Exception {
        Files.writeString(tempDir.resolve("no-role.yaml"), "name: no-role\ndescription: x\n");
        WorkerAgentLoader loader = new WorkerAgentLoader(props(tempDir.toString()));
        assertFalse(loader.names().contains("no-role"));
    }

    @Test
    void missingNameUsesFilename() throws Exception {
        Files.writeString(tempDir.resolve("no-name-agent.yaml"), "description: x\nrole: 你好\n");
        WorkerAgentLoader loader = new WorkerAgentLoader(props(tempDir.toString()));
        assertTrue(loader.names().contains("no-name-agent"));
    }

    @Test
    void disabledIsEmpty() {
        WorkerAgentProperties p = new WorkerAgentProperties();
        p.setEnabled(false);
        p.setDir(tempDir.toString());
        WorkerAgentLoader loader = new WorkerAgentLoader(p);
        assertTrue(loader.list().isEmpty());
    }
}
