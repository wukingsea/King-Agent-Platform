package com.king.ai.compliance;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.king.ai.compliance.access.AccessReviewScheduler;
import com.king.ai.compliance.access.AccessReviewService;
import com.king.ai.system.SpaceService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AccessReviewSchedulerTest {

    private final SpaceService spaces = mock(SpaceService.class);
    private final AccessReviewService reviews = mock(AccessReviewService.class);
    private final AccessReviewScheduler scheduler = new AccessReviewScheduler(spaces, reviews);

    @Test
    void generatesReviewForEverySpaceAsSystem() {
        when(spaces.allSpaceIds()).thenReturn(List.of(1L, 2L, 3L));

        scheduler.quarterly();

        verify(reviews).generate(1L, 0L);
        verify(reviews).generate(2L, 0L);
        verify(reviews).generate(3L, 0L);
        verifyNoMoreInteractions(reviews);
    }

    @Test
    void failureOnOneSpaceIsLoggedWithoutMessageAndDoesNotStopOthers() {
        when(spaces.allSpaceIds()).thenReturn(List.of(1L, 2L, 3L));
        doThrow(new IllegalStateException("jdbc:mysql://db password=p@ss user=admin"))
                .when(reviews).generate(2L, 0L);

        Logger logger = (Logger) LoggerFactory.getLogger(AccessReviewScheduler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatCode(scheduler::quarterly).doesNotThrowAnyException();
        } finally {
            logger.detachAppender(appender);
        }

        verify(reviews).generate(1L, 0L);
        verify(reviews).generate(2L, 0L);
        verify(reviews).generate(3L, 0L);
        assertThat(appender.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage())
                        .contains("spaceId=2")
                        .contains("IllegalStateException")
                        .doesNotContain("password")
                        .doesNotContain("p@ss")
                        .doesNotContain("jdbc")
                        .doesNotContain("user=admin"));
    }

    @Test
    void noSpacesTriggersNothing() {
        when(spaces.allSpaceIds()).thenReturn(List.of());

        scheduler.quarterly();

        verifyNoInteractions(reviews);
    }
}
