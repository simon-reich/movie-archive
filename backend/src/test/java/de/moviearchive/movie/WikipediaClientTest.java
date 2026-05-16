package de.moviearchive.movie;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WikipediaClientTest {

    @Test
    @Disabled("SAVE-04: implement WikipediaClient 6-step fallback first")
    void shouldTryAllSixCandidates_beforeFailing() {}

    @Test
    @Disabled("SAVE-04: implement WikipediaClient first")
    void shouldReturnResult_whenFirstCandidateHits() {}
}
