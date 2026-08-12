package com.sora.sora_agent.multiagent;

import com.sora.sora_agent.config.WorkerAgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
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
 * 专家 worker 加载器：扫描 {@code agents/*.yaml}（classpath 内置）与 {@code app.agent.dir}
 * （文件系统可扩展）目录，解析为 {@link WorkerAgent}。
 *
 * <p>同名专家后加载者覆盖前加载者；单个文件解析失败或缺少 name/role 仅告警跳过。</p>
 */
@Slf4j
@Component
public class WorkerAgentLoader {

    private final Map<String, WorkerAgent> agents = new LinkedHashMap<>();
    private final WorkerAgentProperties props;

    public WorkerAgentLoader(WorkerAgentProperties props) {
        this.props = props;
        if (props.isEnabled()) {
            reload();
        }
    }

    public void reload() {
        agents.clear();
        loadFromClasspath();
        if (props.getDir() != null && !props.getDir().isBlank()) {
            loadFromDir(Path.of(props.getDir()));
        }
        log.info("专家装载完成，共 {} 个: {}", agents.size(), names());
    }

    public List<WorkerAgent> list() {
        return new ArrayList<>(agents.values());
    }

    public List<String> names() {
        return new ArrayList<>(agents.keySet());
    }

    public WorkerAgent get(String name) {
        return name == null ? null : agents.get(name);
    }

    private void loadFromClasspath() {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources("classpath:agents/*.yaml");
            for (Resource r : resources) {
                try (InputStream in = r.getInputStream()) {
                    register(parse(in), r.getFilename());
                } catch (Exception e) {
                    log.warn("解析内置专家失败，跳过: {} - {}", r.getFilename(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("扫描 classpath:agents/*.yaml 失败: {}", e.getMessage());
        }
    }

    private void loadFromDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            log.warn("专家目录不存在，跳过: {}", dir.toAbsolutePath());
            return;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".yaml") || n.endsWith(".yml");
            }).forEach(p -> {
                try (InputStream in = Files.newInputStream(p)) {
                    WorkerAgent agent = parse(in);
                    if (agent.getName() == null || agent.getName().isBlank()) {
                        agent.setName(stripExtension(p.getFileName().toString()));
                    }
                    register(agent, p.getFileName().toString());
                } catch (Exception e) {
                    log.warn("加载专家文件失败，跳过: {} - {}", p.getFileName(), e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("读取专家目录失败: {} - {}", dir, e.getMessage());
        }
    }

    private WorkerAgent parse(InputStream in) {
        Yaml yaml = new Yaml(new Constructor(WorkerAgent.class, new LoaderOptions()));
        return yaml.load(in);
    }

    private void register(WorkerAgent agent, String source) {
        if (agent == null || agent.getName() == null || agent.getName().isBlank()
                || agent.getRole() == null || agent.getRole().isBlank()) {
            log.warn("专家文件缺少 name 或 role，跳过: {}", source);
            return;
        }
        agents.put(agent.getName(), agent);
        log.debug("专家已装载: {} <- {}", agent.getName(), source);
    }

    private String stripExtension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx > 0 ? fileName.substring(0, idx) : fileName;
    }
}
