package com.sora.sora_agent.skill;

import com.sora.sora_agent.config.SkillProperties;
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
 * 技能加载器：扫描 {@code skills/*.yaml}（classpath 内置）与 {@code app.skill.dir}
 * （文件系统可扩展）目录，解析为 {@link Skill}。
 *
 * <p>同名技能后加载者覆盖前加载者（文件系统目录可覆盖 classpath 内置）。
 * 单个文件解析失败仅告警跳过，不影响启动。</p>
 */
@Slf4j
@Component
public class SkillLoader {

    private final Map<String, Skill> skills = new LinkedHashMap<>();
    private final SkillProperties props;

    public SkillLoader(SkillProperties props) {
        this.props = props;
        if (props.isEnabled()) {
            reload();
        }
    }

    /**
     * 重新扫描装载（新增技能文件后调用；当前为启动时装载一次）。
     */
    public void reload() {
        skills.clear();
        loadFromClasspath();
        if (props.getDir() != null && !props.getDir().isBlank()) {
            loadFromDir(Path.of(props.getDir()));
        }
        log.info("技能装载完成，共 {} 个: {}", skills.size(), names());
    }

    /** 全部技能（按加载顺序）。 */
    public List<Skill> list() {
        return new ArrayList<>(skills.values());
    }

    /** 技能名列表。 */
    public List<String> names() {
        return new ArrayList<>(skills.keySet());
    }

    /** 按名取技能；不存在返回 null。 */
    public Skill get(String name) {
        return name == null ? null : skills.get(name);
    }

    private void loadFromClasspath() {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources("classpath:skills/*.yaml");
            for (Resource r : resources) {
                try (InputStream in = r.getInputStream()) {
                    register(parse(in), r.getFilename());
                } catch (Exception e) {
                    log.warn("解析内置技能失败，跳过: {} - {}", r.getFilename(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("扫描 classpath:skills/*.yaml 失败: {}", e.getMessage());
        }
    }

    private void loadFromDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            log.warn("技能目录不存在，跳过: {}", dir.toAbsolutePath());
            return;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".yaml") || n.endsWith(".yml");
            }).forEach(p -> {
                try (InputStream in = Files.newInputStream(p)) {
                    Skill skill = parse(in);
                    if (skill.getName() == null || skill.getName().isBlank()) {
                        skill.setName(stripExtension(p.getFileName().toString()));
                    }
                    register(skill, p.getFileName().toString());
                } catch (Exception e) {
                    log.warn("加载技能文件失败，跳过: {} - {}", p.getFileName(), e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("读取技能目录失败: {} - {}", dir, e.getMessage());
        }
    }

    private Skill parse(InputStream in) {
        Yaml yaml = new Yaml(new Constructor(Skill.class, new LoaderOptions()));
        return yaml.load(in);
    }

    private void register(Skill skill, String source) {
        if (skill == null || skill.getName() == null || skill.getName().isBlank()) {
            log.warn("技能文件缺少 name，跳过: {}", source);
            return;
        }
        skills.put(skill.getName(), skill);
        log.debug("技能已装载: {} <- {}", skill.getName(), source);
    }

    private String stripExtension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx > 0 ? fileName.substring(0, idx) : fileName;
    }
}
