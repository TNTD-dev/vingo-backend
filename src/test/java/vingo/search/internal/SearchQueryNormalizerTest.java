package vingo.search.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SearchQueryNormalizerTest {

    private final SearchQueryNormalizer normalizer = new SearchQueryNormalizer();

    @Test
    void normalize_stripsDiacriticsAndBuildsPrefixQuery() {
        var result = normalizer.normalize("Sơn Tùng").orElseThrow();

        assertThat(result.text()).isEqualTo("son tung");
        assertThat(result.tsQuery()).isEqualTo("son:* & tung:*");
    }

    @Test
    void normalize_foldsStrokedD() {
        assertThat(normalizer.normalize("Đà Nẵng").orElseThrow().text()).isEqualTo("da nang");
    }

    @Test
    void normalize_dropsSpecialCharacters() {
        var result = normalizer.normalize("  M-TP & (sky)  tour:* ' ; ").orElseThrow();

        assertThat(result.text()).isEqualTo("m tp sky tour");
        assertThat(result.tsQuery()).isEqualTo("m:* & tp:* & sky:* & tour:*");
    }

    @Test
    void normalize_returnsEmpty_whenNothingSearchable() {
        assertThat(normalizer.normalize("!!! ... ---")).isEmpty();
        assertThat(normalizer.normalize(null)).isEmpty();
    }

    @Test
    void normalize_limitsNumberAndLengthOfTerms() {
        var result = normalizer.normalize("a b c d e f g h i j " + "x".repeat(100)).orElseThrow();

        assertThat(result.text().split(" ")).hasSize(SearchQueryNormalizer.MAX_TERMS);
        var longTerm = normalizer.normalize("x".repeat(100)).orElseThrow();
        assertThat(longTerm.text()).hasSize(SearchQueryNormalizer.MAX_TERM_LENGTH);
    }
}
