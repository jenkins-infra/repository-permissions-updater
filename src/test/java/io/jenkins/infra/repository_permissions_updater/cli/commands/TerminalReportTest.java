package io.jenkins.infra.repository_permissions_updater.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;

import io.jenkins.infra.repository_permissions_updater.hosting.VerificationMessage;
import io.jenkins.infra.repository_permissions_updater.hosting.VerificationMessage.Severity;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TerminalReportTest {

    private final TerminalReport plain = new TerminalReport(false);

    @Test
    void convertsMarkdownToPlainText() {
        assertThat(plain.inline("See [the docs](https://example.org/docs) and &lt;licenses&gt;"))
                .isEqualTo("See the docs (https://example.org/docs) and <licenses>");
        assertThat(plain.inline("Set `foo` to [https://example.org](https://example.org)"))
                .isEqualTo("Set `foo` to https://example.org");
    }

    @Test
    void colorsCodeSpansOnTerminal() {
        assertThat(new TerminalReport(true).inline("Set `foo`"))
                .isEqualTo("Set \u001B[36mfoo\u001B[0m")
                .doesNotContain("`");
    }

    @Test
    void groupsIssuesBySeverity() {
        Set<VerificationMessage> issues = Set.of(
                new VerificationMessage(Severity.INFO, "An info"),
                new VerificationMessage(
                        Severity.REQUIRED,
                        new HashSet<>(Set.of(new VerificationMessage(Severity.REQUIRED, "A sub item"))),
                        "A required"),
                new VerificationMessage(Severity.WARNING, "A warning"));

        String report = plain.render("https://github.com/me/cool-plugin", "cool-plugin", issues, List.of());

        assertThat(report)
                .contains("⛔ Required (1)\n   • A required\n      - A sub item\n")
                .contains("Warnings (1)\n   • A warning\n")
                .contains("Info (1)\n   • An info\n")
                .contains("✗ 1 required issue to fix before opening a hosting request.")
                .doesNotContain("\u001B[")
                .doesNotContain(":no_entry:");
        assertThat(report.indexOf("Required")).isLessThan(report.indexOf("Warnings"));
        assertThat(report.indexOf("Warnings")).isLessThan(report.indexOf("Info"));
    }

    @Test
    void reportsSuccessAndIncompleteChecks() {
        assertThat(plain.render("u", "n", Set.of(), List.of()))
                .contains("✓ Your repository passes the automated hosting checks.")
                .doesNotContain("recommendations");
        assertThat(plain.render("u", "n", Set.of(), List.of("GitHub")))
                .contains("Checks that could not complete\n   GitHub")
                .contains("not all checks completed");
    }
}
