package vingo.catalog.internal;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Gemini grounded search; không có key thì không suy đoán phí gửi xe. */
@Component
public class GeminiParkingFeeClient {
    private final RestClient http;
    private final GoogleVenueLookupSettings settings;
    public record WebCitation(String title, URI uri, String snippet) {}
    public record GroundedParkingFee(String venueFeeText, Map<String, String> lotFeesByName, List<WebCitation> sources) {}
    public GeminiParkingFeeClient(GoogleVenueLookupSettings settings) {
        this.http = RestClient.builder().baseUrl("https://generativelanguage.googleapis.com/v1beta").build();
        this.settings = settings;
    }
    public Optional<GroundedParkingFee> lookupParkingFee(String venueName, String address, List<String> lotNames) {
        if (settings.geminiApiKey() == null || settings.geminiApiKey().isBlank() || venueName == null || venueName.isBlank()) return Optional.empty();
        String prompt = "Tìm thông tin phí gửi xe hiện tại có nguồn dẫn cho địa điểm " + venueName + ", " +
                (address == null ? "" : address) + ". Các bãi xe cần kiểm tra: " + String.join(", ", lotNames) +
                ". Chỉ nêu mức phí có nguồn; nếu không tìm thấy thì trả lời không rõ.";
        Map<?, ?> response = http.post().uri("/models/{model}:generateContent?key={key}", settings.geminiModel(), settings.geminiApiKey())
                .body(Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                        "tools", List.of(Map.of("google_search", Map.of()))))
                .retrieve().body(Map.class);
        String answer = extractText(response);
        if (answer == null || answer.isBlank() || answer.toLowerCase().contains("không rõ")) return Optional.empty();
        return Optional.of(new GroundedParkingFee(answer.strip(), Map.of(), extractCitations(response)));
    }
    private static String extractText(Map<?, ?> response) {
        if (!(response != null && response.get("candidates") instanceof List<?> candidates) || candidates.isEmpty() || !(candidates.getFirst() instanceof Map<?, ?> candidate)) return null;
        if (!(candidate.get("content") instanceof Map<?, ?> content) || !(content.get("parts") instanceof List<?> parts)) return null;
        StringBuilder result = new StringBuilder();
        for (Object part : parts) if (part instanceof Map<?, ?> map && map.get("text") instanceof String text) result.append(text);
        return result.toString();
    }
    private static List<WebCitation> extractCitations(Map<?, ?> response) {
        try {
            Map<?, ?> candidate = (Map<?, ?>) ((List<?>) response.get("candidates")).getFirst();
            Map<?, ?> metadata = (Map<?, ?>) candidate.get("groundingMetadata");
            Map<?, ?> grounding = (Map<?, ?>) metadata.get("groundingAttributions");
            Object raw = grounding.get("groundingChunks");
            if (!(raw instanceof List<?> chunks)) return List.of();
            return chunks.stream().filter(Map.class::isInstance).map(Map.class::cast).map(chunk -> {
                Map<?, ?> web = chunk.get("web") instanceof Map<?, ?> w ? w : Map.of();
                try { return new WebCitation(String.valueOf(web.get("title")), URI.create(String.valueOf(web.get("uri"))), null); }
                catch (IllegalArgumentException e) { return null; }
            }).filter(c -> c != null).toList();
        } catch (RuntimeException e) { return List.of(); }
    }
}
