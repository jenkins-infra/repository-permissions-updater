package io.jenkins.infra.repository_permissions_updater.cli.commands;

import io.jenkins.infra.repository_permissions_updater.hosting.VerificationMessage;
import io.jenkins.infra.repository_permissions_updater.hosting.VerificationMessage.Severity;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders hosting check results for a terminal rather than as a GitHub comment:
 * emoji instead of GitHub shortcodes, issues grouped by severity, and Markdown links and code spans
 * turned into plain text, with ANSI colors when writing to an interactive terminal.
 */
final class TerminalReport {

    private static final Pattern LINK = Pattern.compile("\\[([^]]+)]\\(([^)\\s]+)\\)");
    private static final Pattern CODE = Pattern.compile("`([^`]+)`");

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String CYAN = "\u001B[36m";

    private final boolean color;

    TerminalReport(boolean color) {
        this.color = color;
    }

    /**
     * Whether stdout is an interactive terminal that should get colors, honoring https://no-color.org.
     */
    static boolean colorSupported() {
        var console = System.console();
        return console != null
                && console.isTerminal()
                && System.getenv("NO_COLOR") == null
                && !"dumb".equals(System.getenv("TERM"));
    }

    String progress(String verification) {
        return style(DIM, "  Checking " + verification + "...");
    }

    String render(
            String repositoryUrl, String newRepositoryName, Set<VerificationMessage> issues, List<String> failed) {
        StringBuilder out = new StringBuilder("\n");
        out.append(style(BOLD, "Hosting check results"))
                .append(" for ")
                .append(repositoryUrl)
                .append('\n')
                .append(style(DIM, "New repository name: " + newRepositoryName))
                .append("\n\n");

        for (Severity severity : List.of(Severity.REQUIRED, Severity.WARNING, Severity.INFO)) {
            List<VerificationMessage> ofSeverity = issues.stream()
                    .filter(i -> i.getSeverity() == severity)
                    .sorted(Comparator.reverseOrder())
                    .toList();
            if (ofSeverity.isEmpty()) {
                continue;
            }
            out.append(style(BOLD + colorOf(severity), emojiOf(severity) + " " + titleOf(severity)))
                    .append(style(DIM, " (" + ofSeverity.size() + ")"))
                    .append('\n');
            appendItems(out, ofSeverity, 1);
            out.append('\n');
        }

        if (!failed.isEmpty()) {
            out.append(style(BOLD + RED, "💥 Checks that could not complete"))
                    .append('\n')
                    .append("   ")
                    .append(String.join(", ", failed))
                    .append(style(DIM, " (see the errors above)"))
                    .append("\n\n");
        }

        long required = issues.stream()
                .filter(i -> i.getSeverity() == Severity.REQUIRED)
                .count();
        if (required > 0) {
            out.append(style(
                    BOLD + RED,
                    "✗ " + required + " required " + (required == 1 ? "issue" : "issues")
                            + " to fix before opening a hosting request."));
            out.append('\n').append("  Push your fixes to GitHub, then run the check again.");
        } else if (!failed.isEmpty()) {
            out.append(style(
                    BOLD + YELLOW,
                    "! No required issues found, but not all checks completed. Fix the errors and re-run."));
        } else {
            out.append(style(BOLD + GREEN, "✓ Your repository passes the automated hosting checks."));
            if (!issues.isEmpty()) {
                out.append('\n')
                        .append(
                                "  Warnings and info items are recommendations and will not stall the hosting process.");
            }
        }
        return out.append('\n').toString();
    }

    private void appendItems(StringBuilder out, Collection<VerificationMessage> items, int depth) {
        String indent = "   ".repeat(depth);
        String bullet = depth == 1 ? "• " : "- ";
        for (VerificationMessage item :
                items.stream().sorted(Comparator.reverseOrder()).toList()) {
            out.append(indent).append(bullet).append(inline(item.getMessage())).append('\n');
            if (!item.getSubItems().isEmpty()) {
                appendItems(out, item.getSubItems(), depth + 1);
            }
        }
    }

    /**
     * Converts the Markdown and HTML entities used in verification messages to terminal text.
     */
    String inline(String markdown) {
        String text = markdown.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        text = LINK.matcher(text).replaceAll(m -> {
            String label = m.group(1);
            String url = m.group(2);
            return Matcher.quoteReplacement(
                    label.equals(url) ? style(BLUE, url) : label + " " + style(BLUE, "(" + url + ")"));
        });
        if (color) {
            text = CODE.matcher(text).replaceAll(m -> Matcher.quoteReplacement(style(CYAN, m.group(1))));
        }
        return text;
    }

    private String style(String codes, String text) {
        return color ? codes + text + RESET : text;
    }

    private static String emojiOf(Severity severity) {
        return switch (severity) {
            case REQUIRED -> "⛔";
            case WARNING -> "⚠️ ";
            case INFO -> "ℹ️ ";
        };
    }

    private static String colorOf(Severity severity) {
        return switch (severity) {
            case REQUIRED -> RED;
            case WARNING -> YELLOW;
            case INFO -> BLUE;
        };
    }

    private static String titleOf(Severity severity) {
        return switch (severity) {
            case REQUIRED -> "Required";
            case WARNING -> "Warnings";
            case INFO -> "Info";
        };
    }
}
