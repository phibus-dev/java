package dev.phibus.s3.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RunStatusTest {
    @Test
    void parsesStatusesCaseInsensitivelyAndIdentifiesTerminalStates() {
        assertThat(RunStatus.parse(" completed_with_errors ")).isEqualTo(RunStatus.COMPLETED_WITH_ERRORS);
        assertThat(RunStatus.parse("running").terminal()).isFalse();
        assertThat(RunStatus.parse("cancelled").terminal()).isTrue();
    }

    @Test
    void rejectsMissingAndUnsupportedStatuses() {
        assertThatThrownBy(() -> RunStatus.parse(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
        assertThatThrownBy(() -> RunStatus.parse("stopped"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported");
    }

    @Test
    void preservesUnknownStopReasonsWithoutBreakingTheUnifiedApi() {
        assertThat(RunStopReason.parseNullable(null)).isNull();
        assertThat(RunStopReason.parseNullable("recovery_timeout")).isEqualTo(RunStopReason.RECOVERY_TIMEOUT);
        assertThat(RunStopReason.parseNullable("future_reason")).isEqualTo(RunStopReason.UNKNOWN);
    }
}
