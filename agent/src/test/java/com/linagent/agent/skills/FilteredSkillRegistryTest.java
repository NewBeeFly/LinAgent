package com.linagent.agent.skills;

import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FilteredSkillRegistryTest {

    @TempDir
    Path skillsRoot;
    private SkillRegistry inner;
    private FilteredSkillRegistry filtered;

    @BeforeEach
    void setUp() throws Exception {
        writeSkill("resident-skill", true);
        writeSkill("progressive-skill", false);
        // userSkillsDirectory 指向不存在的目录，避免默认 ~/saa/skills 引入环境依赖
        inner = FileSystemSkillRegistry.builder()
            .projectSkillsDirectory(skillsRoot.toString())
            .userSkillsDirectory(skillsRoot.resolve("no-user-skills").toString())
            .build();
        filtered = new FilteredSkillRegistry(inner, Set.of("resident-skill"));
    }

    private void writeSkill(String name, boolean resident) throws Exception {
        Path dir = skillsRoot.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
            ---
            name: %s
            description: %s 技能
            %s
            ---
            %s 的正文
            """.formatted(name, name, resident ? "resident: true" : "", name));
    }

    @Test
    void listAllHidesResidentSkills() {
        List<SkillMetadata> all = filtered.listAll();
        assertThat(all).extracting(SkillMetadata::getName).containsExactly("progressive-skill");
    }

    @Test
    void containsHidesResidentSkills() {
        assertThat(filtered.contains("resident-skill")).isFalse();
        assertThat(filtered.contains("progressive-skill")).isTrue();
    }

    @Test
    void sizeCountsOnlyProgressive() {
        assertThat(filtered.size()).isEqualTo(1);
    }

    @Test
    void readSkillContentStillWorksForProgressive() throws Exception {
        String content = filtered.readSkillContent("progressive-skill");
        assertThat(content).contains("progressive-skill 的正文");
    }

    @Test
    void residentSkillHiddenFromGetAndRead() {
        assertThat(filtered.get("resident-skill")).isEmpty();
        assertThat(filtered.get("progressive-skill")).isPresent();
        assertThatThrownBy(() -> filtered.readSkillContent("resident-skill"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void listAllSortedByNameForStablePromptPrefix() {
        // delegate 返回顺序不受控（FileSystemSkillRegistry 底层 HashMap 迭代序无保证），
        // listAll 必须按 name 字典序输出：buildSkillsPrompt 消费 listAll，顺序抖动 =
        // 拼出的技能清单逐字节变化 = LLM 前缀缓存全量失效
        SkillRegistry unordered = mock(SkillRegistry.class);
        when(unordered.listAll()).thenReturn(List.of(
            meta("zeta-skill"), meta("alpha-skill"), meta("resident-skill"), meta("mid-skill")));
        FilteredSkillRegistry r = new FilteredSkillRegistry(unordered, Set.of("resident-skill"));
        assertThat(r.listAll())
            .extracting(SkillMetadata::getName)
            .containsExactly("alpha-skill", "mid-skill", "zeta-skill");
    }

    private static SkillMetadata meta(String name) {
        SkillMetadata m = new SkillMetadata();
        m.setName(name);
        return m;
    }

    @Test
    void searchHidesResidentSkills() {
        assertThat(filtered.search("resident"))
            .extracting(SkillMetadata::getName)
            .isEmpty();
        assertThat(filtered.search(""))
            .extracting(SkillMetadata::getName)
            .containsExactly("progressive-skill");
    }
}
