package com.linagent.agent.approval;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * InMemorySessionRules：key=tenant:user:conv:tool 四段作用域隔离、Set 语义幂等、
 * evict(conversationId) 按 conv 段清理、patterns 返回不可变快照。
 */
class InMemorySessionRulesTest {

    private final InMemorySessionRules rules = new InMemorySessionRules();

    @Test
    void patternsReturnsEmptySetByDefaultAndScopesByAllFourSegments() {
        assertThat(rules.patterns("default", "linmj", 42L, "shell")).isEmpty();

        rules.add("default", "linmj", 42L, "shell", "docker *");

        assertThat(rules.patterns("default", "linmj", 42L, "shell")).containsExactly("docker *");
        // 四段任一不同都不命中
        assertThat(rules.patterns("other", "linmj", 42L, "shell")).isEmpty();
        assertThat(rules.patterns("default", "someone", 42L, "shell")).isEmpty();
        assertThat(rules.patterns("default", "linmj", 43L, "shell")).isEmpty();
        assertThat(rules.patterns("default", "linmj", 42L, "write_file")).isEmpty();
    }

    @Test
    void addIsIdempotentSetSemantics() {
        rules.add("default", "linmj", 42L, "shell", "docker *");
        rules.add("default", "linmj", 42L, "shell", "docker *");
        rules.add("default", "linmj", 42L, "shell", "pip install *");

        assertThat(rules.patterns("default", "linmj", 42L, "shell"))
                .containsExactlyInAnyOrder("docker *", "pip install *");
    }

    @Test
    void evictRemovesOnlyTheGivenConversationAcrossToolsAndTenants() {
        rules.add("default", "linmj", 42L, "shell", "docker *");
        rules.add("default", "linmj", 42L, "write_file", "reports/*");
        rules.add("other", "linmj", 42L, "shell", "docker *");
        rules.add("default", "linmj", 43L, "shell", "docker *");

        rules.evict(42L);

        assertThat(rules.patterns("default", "linmj", 42L, "shell")).isEmpty();
        assertThat(rules.patterns("default", "linmj", 42L, "write_file")).isEmpty();
        assertThat(rules.patterns("other", "linmj", 42L, "shell")).isEmpty();
        // 其它会话不受影响
        assertThat(rules.patterns("default", "linmj", 43L, "shell")).containsExactly("docker *");
    }

    @Test
    void patternsReturnsUnmodifiableSnapshot() {
        rules.add("default", "linmj", 42L, "shell", "docker *");

        Set<String> snapshot = rules.patterns("default", "linmj", 42L, "shell");
        assertThatThrownBy(() -> snapshot.add("rm *"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
