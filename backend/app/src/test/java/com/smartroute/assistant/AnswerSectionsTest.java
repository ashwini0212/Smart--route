package com.smartroute.assistant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The section parser, including the shapes a model actually produces instead of the one it was asked for. */
class AnswerSectionsTest {

    @Test
    void readsPlainHeadingsAndBullets() {
        AnswerSections.Parsed parsed = AnswerSections.parse("""
                FACTS
                - 12 orders are waiting.
                - The oldest has waited 40 minutes.

                RECOMMENDATIONS
                - Assign the oldest first.

                UNCERTAINTY
                - Positions are simulated.
                """);

        assertThat(parsed.complete()).isTrue();
        assertThat(parsed.facts()).containsExactly("12 orders are waiting.", "The oldest has waited 40 minutes.");
        assertThat(parsed.recommendations()).containsExactly("Assign the oldest first.");
        assertThat(parsed.uncertainty()).containsExactly("Positions are simulated.");
    }

    @Test
    void readsMarkdownHeadingsNumberedListsAndColons() {
        AnswerSections.Parsed parsed = AnswerSections.parse("""
                ## Facts
                1. Two warehouses are active.

                **RECOMMENDATIONS:**
                * Nothing urgent.

                ### uncertainty
                • No data older than today.
                """);

        assertThat(parsed.complete()).isTrue();
        assertThat(parsed.facts()).containsExactly("Two warehouses are active.");
        assertThat(parsed.recommendations()).containsExactly("Nothing urgent.");
        assertThat(parsed.uncertainty()).containsExactly("No data older than today.");
    }

    @Test
    void reportsAnIncompleteAnswerRatherThanGuessingWhereASectionEnds() {
        AnswerSections.Parsed parsed = AnswerSections.parse("""
                FACTS
                - 3 drivers are available.

                RECOMMENDATIONS
                - Dispatch two of them.
                """);

        assertThat(parsed.complete()).isFalse();
        assertThat(parsed.facts()).containsExactly("3 drivers are available.");
        assertThat(parsed.uncertainty()).isEmpty();
    }

    @Test
    void ignoresTextBeforeTheFirstHeading() {
        AnswerSections.Parsed parsed = AnswerSections.parse("""
                Here is what I found.

                FACTS
                - One order is late.
                RECOMMENDATIONS
                - Call the driver.
                UNCERTAINTY
                - The delay reason is not recorded.
                """);

        assertThat(parsed.facts()).containsExactly("One order is late.");
        assertThat(parsed.complete()).isTrue();
    }
}
