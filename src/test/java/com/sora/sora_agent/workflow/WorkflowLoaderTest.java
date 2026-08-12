package com.sora.sora_agent.workflow;

import com.sora.sora_agent.config.WorkflowProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkflowLoader} 单元测试 — 用临时目录 fixture YAML，离线可跑。
 *
 * <p>注意：测试 classpath 含内置工作流 {@code workflows/research-report.yaml}，
 * 因此列表断言使用 contains 而非精确尺寸。</p>
 */
class WorkflowLoaderTest {

    @TempDir
    Path tempDir;

    private WorkflowProperties props(String dir) {
        WorkflowProperties p = new WorkflowProperties();
        p.setEnabled(true);
        p.setDir(dir);
        return p;
    }

    @Test
    void loadsWorkflowFromDir() throws Exception {
        Files.writeString(tempDir.resolve("wf.yaml"), """
                name: test-wf
                description: 测试流程
                input:
                  - topic
                steps:
                  - id: a
                    type: llm
                    prompt: "关于 {{input.topic}} 总结"
                  - id: b
                    type: tool
                    tool: generatePDF
                    params:
                      fileName: "{{input.topic}}.pdf"
                """);
        WorkflowLoader loader = new WorkflowLoader(props(tempDir.toString()));

        assertTrue(loader.names().contains("test-wf"));
        Workflow wf = loader.get("test-wf");
        assertNotNull(wf);
        assertEquals("测试流程", wf.getDescription());
        assertEquals(2, wf.getSteps().size());
        assertEquals("llm", wf.getSteps().get(0).getType());
        assertEquals("generatePDF", wf.getSteps().get(1).getTool());
        assertTrue(wf.getSteps().get(0).getPrompt().contains("{{input.topic}}"));
    }

    @Test
    void missingDirIsTolerated() {
        WorkflowLoader loader = new WorkflowLoader(props(tempDir.resolve("nope").toString()));
        // 目录不存在仅告警；classpath 内置工作流仍加载，不抛异常
        assertNotNull(loader.list());
    }

    @Test
    void badYamlIsSkipped() throws Exception {
        Files.writeString(tempDir.resolve("bad.yaml"), "name: [unclosed\n  invalid: :::");
        Files.writeString(tempDir.resolve("good.yaml"), "name: good-wf\ndescription: ok\nsteps:\n  - id: s\n    type: llm\n    prompt: hi\n");
        WorkflowLoader loader = new WorkflowLoader(props(tempDir.toString()));
        assertTrue(loader.names().contains("good-wf"));
        assertFalse(loader.names().contains("bad"));
    }

    @Test
    void workflowWithoutStepsIsSkipped() throws Exception {
        Files.writeString(tempDir.resolve("no-steps.yaml"), "name: no-steps\ndescription: x\n");
        WorkflowLoader loader = new WorkflowLoader(props(tempDir.toString()));
        assertFalse(loader.names().contains("no-steps"));
    }

    @Test
    void missingNameUsesFilename() throws Exception {
        Files.writeString(tempDir.resolve("no-name-wf.yaml"), "description: x\nsteps:\n  - id: s\n    type: llm\n    prompt: hi\n");
        WorkflowLoader loader = new WorkflowLoader(props(tempDir.toString()));
        assertTrue(loader.names().contains("no-name-wf"));
    }

    @Test
    void disabledIsEmpty() {
        WorkflowProperties p = new WorkflowProperties();
        p.setEnabled(false);
        p.setDir(tempDir.toString());
        WorkflowLoader loader = new WorkflowLoader(p);
        assertTrue(loader.list().isEmpty());
    }
}
