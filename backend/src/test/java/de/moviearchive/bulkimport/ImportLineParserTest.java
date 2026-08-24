package de.moviearchive.bulkimport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImportLineParserTest {

    private final ImportLineParser parser = new ImportLineParser();

    @Test
    void shouldReturnNull_forBlankLine() {
        assertThat(parser.parse("   ")).isNull();
    }

    @Test
    void shouldParseValidLine_withEmptyOriginalTitle() {
        ImportLineParser.ParsedLine parsed = parser.parse("Inception;;2010");

        assertThat(parsed.valid()).isTrue();
        assertThat(parsed.title()).isEqualTo("Inception");
        assertThat(parsed.originalTitle()).isNull();
        assertThat(parsed.year()).isEqualTo(2010);
    }

    @Test
    void shouldParseValidLine_withOriginalTitle() {
        ImportLineParser.ParsedLine parsed = parser.parse("Robin Hood;Robin des Bois;2010");

        assertThat(parsed.valid()).isTrue();
        assertThat(parsed.title()).isEqualTo("Robin Hood");
        assertThat(parsed.originalTitle()).isEqualTo("Robin des Bois");
        assertThat(parsed.year()).isEqualTo(2010);
    }

    @Test
    void shouldBeInvalid_whenFieldCountIsWrong() {
        ImportLineParser.ParsedLine parsed = parser.parse("Title;2010");

        assertThat(parsed.valid()).isFalse();
        assertThat(parsed.year()).isNull();
    }

    @Test
    void shouldBeInvalid_whenYearIsNonNumeric() {
        ImportLineParser.ParsedLine parsed = parser.parse("Title;;abcd");

        assertThat(parsed.valid()).isFalse();
        assertThat(parsed.title()).isEqualTo("Title");
        assertThat(parsed.year()).isNull();
    }

    @Test
    void shouldBeInvalid_whenTitleIsEmpty() {
        ImportLineParser.ParsedLine parsed = parser.parse(";;2010");

        assertThat(parsed.valid()).isFalse();
        assertThat(parsed.year()).isNull();
    }
}
