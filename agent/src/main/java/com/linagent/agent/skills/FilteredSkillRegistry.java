package com.linagent.agent.skills;

import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 委托 SkillRegistry（FileSystemSkillRegistry），但对 SkillsAgentHook 隐藏常驻技能：
 * 常驻技能全文已注入 system prompt，无需再走 read_skill 渐进披露。
 * search / readSkillContentByPath / disableByPath 走接口 default 实现，
 * 它们经由 this.listAll()/this.getByPath()/this.disable() 分派，天然继承过滤语义。
 */
public class FilteredSkillRegistry implements SkillRegistry {

    private final SkillRegistry delegate;
    private final Set<String> residentNames;

    public FilteredSkillRegistry(SkillRegistry delegate, Set<String> residentNames) {
        this.delegate = delegate;
        this.residentNames = Set.copyOf(residentNames);
    }

    @Override
    public List<SkillMetadata> listAll() {
        return delegate.listAll().stream()
            .filter(m -> !residentNames.contains(m.getName()))
            .toList();
    }

    @Override
    public boolean contains(String skillName) {
        return !residentNames.contains(skillName) && delegate.contains(skillName);
    }

    @Override
    public Optional<SkillMetadata> get(String skillName) {
        return residentNames.contains(skillName) ? Optional.empty() : delegate.get(skillName);
    }

    @Override
    public Optional<SkillMetadata> getByPath(String skillPath) {
        return delegate.getByPath(skillPath)
            .filter(m -> !residentNames.contains(m.getName()));
    }

    @Override
    public int size() {
        return listAll().size();
    }

    @Override
    public boolean disable(String skillName) {
        return !residentNames.contains(skillName) && delegate.disable(skillName);
    }

    @Override
    public boolean isDisabled(String skillName) {
        return !residentNames.contains(skillName) && delegate.isDisabled(skillName);
    }

    @Override
    public void reload() {
        delegate.reload();
    }

    @Override
    public String readSkillContent(String skillName) throws IOException {
        if (residentNames.contains(skillName)) {
            // 与 delegate 的"技能不存在"行为对齐：常驻技能对 read_skill 不可见
            throw new IllegalStateException("Skill not found: " + skillName);
        }
        return delegate.readSkillContent(skillName);
    }

    @Override
    public String getSkillLoadInstructions() {
        return delegate.getSkillLoadInstructions();
    }

    @Override
    public String getRegistryType() {
        return delegate.getRegistryType();
    }

    @Override
    public SystemPromptTemplate getSystemPromptTemplate() {
        return delegate.getSystemPromptTemplate();
    }
}
