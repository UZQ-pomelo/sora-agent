package com.sora.sora_agent.workflow;

import com.sora.sora_agent.config.WorkflowProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流加载器：扫描 {@code workflows/*.yaml}（classpath 内置）与 {@code app.workflow.dir}
 * （文件系统可扩展）目录，解析为 {@link Workflow}。
 *
 * <p>同名工作流后加载者覆盖前加载者（文件系统目录可覆盖 classpath 内置）。
 * 单个文件解析失败或缺少 name/steps 仅告警跳过，不影响启动。</p>
 */
@Slf4j
@Component
public class WorkflowLoader {

    private final Map<String, Workflow> workflows = new LinkedHashMap<>();
    private final WorkflowProperties props;

    public WorkflowLoader(WorkflowProperties props) {
        this.props = props;
        if (props.isEnabled()) {
            reload();
        }
    }

    /**
     * 重新扫描装载（当前为启动时装载一次）。
     */
    public void reload() {
        workflows.clear();
        loadFromClasspath();
        if (props.getDir() != null && !props.getDir().isBlank()) {
            loadFromDir(Path.of(props.getDir()));
        }
        log.info("工作流装载完成，共 {} 个: {}", workflows.size(), names());
    }

    /** 全部工作流（按加载顺序）。 */
    public List<Workflow> list() {
        return new ArrayList<>(workflows.values());
    }

    /** 工作流名列表。 */
    public List<String> names() {
        return new ArrayList<>(workflows.keySet());
    }

    /** 按名取工作流；不存在返回 null。 */
    public Workflow get(String name) {
        return name == null ? null : workflows.get(name);
    }

    private void loadFromClasspath() {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources("classpath:workflows/*.yaml");
            for (Resource r : resources) {
                try (InputStream in = r.getInputStream()) {
                    register(parse(in), r.getFilename());
                } catch (Exception e) {
                    log.warn("解析内置工作流失败，跳过: {} - {}", r.getFilename(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("扫描 classpath:workflows/*.yaml 失败: {}", e.getMessage());
        }
    }

    private void loadFromDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            log.warn("工作流目录不存在，跳过: {}", dir.toAbsolutePath());
            return;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".yaml") || n.endsWith(".yml");
            }).forEach(p -> {
                try (InputStream in = Files.newInputStream(p)) {
                    Workflow workflow = parse(in);
                    if (workflow.getName() == null || workflow.getName().isBlank()) {
                        workflow.setName(stripExtension(p.getFileName().toString()));
                    }
                    register(workflow, p.getFileName().toString());
                } catch (Exception e) {
                    log.warn("加载工作流文件失败，跳过: {} - {}", p.getFileName(), e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("读取工作流目录失败: {} - {}", dir, e.getMessage());
        }
    }

    private Workflow parse(InputStream in) {
        Yaml yaml = new Yaml(new Constructor(Workflow.class));
        return yaml.load(in);
    }

    private void register(Workflow workflow, String source) {
        if (workflow == null || workflow.getName() == null || workflow.getName().isBlank()
                || workflow.getSteps() == null || workflow.getSteps().isEmpty()) {
            log.warn("工作流文件缺少 name 或 steps，跳过: {}", source);
            return;
        }
        boolean stepsValid = workflow.getSteps().stream()
                .allMatch(s -> s != null && s.getId() != null && !s.getId().isBlank()
                        && s.getType() != null
                        && ("tool".equalsIgnoreCase(s.getType().trim())
                            || "llm".equalsIgnoreCase(s.getType().trim())));
        if (!stepsValid) {
            log.warn("工作流步骤缺少 id 或 type 非法（仅 tool/llm），跳过: {}", source);
            return;
        }
        workflows.put(workflow.getName(), workflow);
        log.debug("工作流已装载: {} <- {}", workflow.getName(), source);
    }

    private String stripExtension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx > 0 ? fileName.substring(0, idx) : fileName;
    }
}
