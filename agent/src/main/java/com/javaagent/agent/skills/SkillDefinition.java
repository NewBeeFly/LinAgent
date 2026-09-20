package com.javaagent.agent.skills;

import java.nio.file.Path;

public record SkillDefinition(String name, String description, boolean resident, Path dir, String content) {
}
