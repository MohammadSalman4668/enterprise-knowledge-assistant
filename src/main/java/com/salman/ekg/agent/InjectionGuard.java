package com.salman.ekg.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Treats retrieved text as untrusted data. Sentences that look like instructions to an
 * AI (override attempts, role changes, fake system messages, privilege or data requests,
 * delimiter spoofing) are replaced with a marker and the run is flagged.
 *
 * <p>This is one layer, not the whole defence: even unsanitised text could not change
 * permissions, because the role comes from the security context and tools are
 * allow-listed Java methods. The guard keeps the model's input clean and makes the
 * attempt visible in the trace.</p>
 */
@Component
public class InjectionGuard {

    public static final String REMOVED_MARKER = "[removed: instruction-like text]";

    private record Rule(String name, Pattern pattern) {}

    private static final List<Rule> RULES = List.of(
            new Rule("override-instructions", Pattern.compile(
                    "(?i)\\b(ignore|disregard|forget|override)\\b.{0,40}\\b(previous|prior|above|earlier|all|any|system|your)\\b.{0,20}\\b(instructions?|rules|prompts?|guidelines|directives)\\b")),
            new Rule("role-reassignment", Pattern.compile(
                    "(?i)\\byou are now\\b|\\bact as (an? )?(admin|administrator|root|system|developer)\\b|\\b(administrator|admin|developer|god|jailbreak) mode\\b")),
            new Rule("fake-system-message", Pattern.compile(
                    "(?i)(^|[\\s\\[(])(system|assistant|developer)\\s*(prompt|message|note|instruction)?\\s*:")),
            new Rule("privilege-escalation", Pattern.compile(
                    "(?i)\\b(grant|give|elevate|escalate|assign)\\b.{0,40}\\b(access|permissions?|role|privileges?|admin)\\b")),
            new Rule("data-exfiltration", Pattern.compile(
                    "(?i)\\b(reveal|print|leak|dump|disclose|exfiltrate)\\b.{0,60}\\b(restricted|confidential|system prompt|secrets?|passwords?|api keys?|salary|salaries)\\b")),
            new Rule("delimiter-spoofing", Pattern.compile(
                    "(?i)</?\\s*(evidence|system|instructions?|tool_result|tool_use)\\b[^>]*>")));

    private static final Pattern SENTENCE = Pattern.compile("(?<=[.!?])\\s+");

    public record GuardResult(String text, List<String> flags) {}

    public GuardResult sanitize(String content) {
        if (content == null || content.isEmpty()) {
            return new GuardResult("", List.of());
        }
        Set<String> flags = new LinkedHashSet<>();
        List<String> lines = new ArrayList<>();
        for (String line : content.split("\\R", -1)) {
            List<String> kept = new ArrayList<>();
            for (String sentence : SENTENCE.split(line)) {
                List<String> hits = RULES.stream().filter(r -> r.pattern().matcher(sentence).find()).map(Rule::name).toList();
                if (hits.isEmpty()) {
                    kept.add(sentence);
                } else {
                    flags.addAll(hits);
                    if (kept.isEmpty() || !REMOVED_MARKER.equals(kept.getLast())) {
                        kept.add(REMOVED_MARKER);
                    }
                }
            }
            lines.add(String.join(" ", kept));
        }
        return new GuardResult(String.join("\n", lines), List.copyOf(flags));
    }
}
