package vingo.search.internal;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import vingo.search.MatchMode;

/**
 * Chuẩn hoá từ khoá: bỏ dấu, viết thường, lọc ký tự đặc biệt, dựng tsquery prefix ({@code son:* & tung:*}).
 */
@Component
class SearchQueryNormalizer {

    static final int MAX_TERMS = 8;
    static final int MAX_TERM_LENGTH = 40;

    /**
     * @param text    từ khoá đã chuẩn hoá, các từ cách nhau một dấu cách (dùng cho trigram)
     * @param tsQuery chuỗi cho {@code to_tsquery('simple', ...)}, khớp chặt (nối bằng {@code &})
     * @param looseTsQuery tsquery khớp lỏng (nối bằng {@code |}, bỏ từ 1 ký tự); rỗng khi không nới được
     */
    record NormalizedQuery(String text, String tsQuery, String looseTsQuery) {

        /** Nới được khi có ít nhất 2 từ và tsquery lỏng không rỗng. */
        boolean canLoosen() {
            return text.indexOf(' ') >= 0 && !looseTsQuery.isEmpty();
        }

        String tsQuery(MatchMode mode) {
            return mode == MatchMode.LOOSE ? looseTsQuery : tsQuery;
        }
    }

    /** Rỗng khi từ khoá không còn ký tự tìm được (ví dụ chỉ toàn dấu câu). */
    Optional<NormalizedQuery> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        // NFD không tách được đ/Đ nên đổi tay, giống luật của unaccent.
        String folded = Normalizer.normalize(raw.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .strip();
        if (folded.isEmpty()) {
            return Optional.empty();
        }
        String[] terms = Arrays.stream(folded.split(" "))
                .map(t -> t.length() > MAX_TERM_LENGTH ? t.substring(0, MAX_TERM_LENGTH) : t)
                .limit(MAX_TERMS)
                .toArray(String[]::new);
        String text = String.join(" ", terms);
        String tsQuery = Arrays.stream(terms).map(t -> t + ":*").collect(Collectors.joining(" & "));
        // Khớp lỏng bỏ từ 1 ký tự (như "m", "a") vì prefix 1 ký tự khớp gần như mọi thứ.
        String looseTsQuery = Arrays.stream(terms).filter(t -> t.length() > 1)
                .map(t -> t + ":*").collect(Collectors.joining(" | "));
        return Optional.of(new NormalizedQuery(text, tsQuery, looseTsQuery));
    }
}
