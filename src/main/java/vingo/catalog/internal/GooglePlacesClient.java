package vingo.catalog.internal;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Google Places API (New); không cấu hình key thì cho phép dùng các chức năng khác của catalog. */
@Component
public class GooglePlacesClient {
    private static final String BASE = "https://places.googleapis.com/v1";
    private static final String SEARCH_FIELDS = "places.id,places.displayName,places.formattedAddress,places.location,places.googleMapsUri,places.types,places.regularOpeningHours";
    private static final String DETAIL_FIELDS = "id,displayName,formattedAddress,location,googleMapsUri,types,regularOpeningHours";
    private final RestClient http;
    private final GoogleVenueLookupSettings settings;

    public enum ParkingType { FREE, PAID, FREE_AND_PAID, UNKNOWN }
    public record OpeningHoursData(Boolean openNow, List<String> weekdayDescriptions, String summary) {}
    public record PlaceDetails(String placeId, String name, String formattedAddress, String cityCode,
            String district, BigDecimal lat, BigDecimal lng, URI googleMapsUri,
            OpeningHoursData openingHours, ParkingType parkingType) {}

    public GooglePlacesClient(GoogleVenueLookupSettings settings) {
        var client = HttpClient.newBuilder().connectTimeout(settings.timeout()).build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(settings.timeout());
        this.http = RestClient.builder().requestFactory(factory).baseUrl(BASE).build();
        this.settings = settings;
    }

    public List<PlaceDetails> searchText(String query, int limit) {
        if (!configured()) return List.of();
        Map<?, ?> response = http.post().uri("/places:searchText")
                .header("X-Goog-Api-Key", settings.googleApiKey())
                .header("X-Goog-FieldMask", SEARCH_FIELDS)
                .body(Map.of("textQuery", query, "pageSize", limit))
                .retrieve().body(Map.class);
        return parsePlaces(response == null ? null : response.get("places"));
    }

    public PlaceDetails getPlaceDetails(String placeId) {
        if (!configured()) return null;
        Map<?, ?> response = http.get().uri("/places/{id}", placeId)
                .header("X-Goog-Api-Key", settings.googleApiKey())
                .header("X-Goog-FieldMask", DETAIL_FIELDS)
                .retrieve().body(Map.class);
        return parsePlace(response);
    }

    public List<PlaceDetails> searchNearbyParking(BigDecimal lat, BigDecimal lng, int radiusMeters, int limit) {
        if (!configured()) return List.of();
        Map<String, Object> body = Map.of("includedTypes", List.of("parking"), "maxResultCount", Math.min(limit, 20),
                "locationRestriction", Map.of("circle", Map.of("center", Map.of("latitude", lat, "longitude", lng), "radius", (double) radiusMeters)));
        Map<?, ?> response = http.post().uri("/places:searchNearby")
                .header("X-Goog-Api-Key", settings.googleApiKey()).header("X-Goog-FieldMask", SEARCH_FIELDS)
                .body(body).retrieve().body(Map.class);
        return parsePlaces(response == null ? null : response.get("places"));
    }

    private boolean configured() { return settings.googleApiKey() != null && !settings.googleApiKey().isBlank(); }
    private static List<PlaceDetails> parsePlaces(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<PlaceDetails> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> map) { PlaceDetails p = parsePlace(map); if (p != null) result.add(p); }
        return List.copyOf(result);
    }
    private static PlaceDetails parsePlace(Map<?, ?> map) {
        if (map == null) return null;
        String id = string(map.get("id"));
        String name = nestedString(map.get("displayName"), "text");
        String address = string(map.get("formattedAddress"));
        Map<?, ?> location = map.get("location") instanceof Map<?, ?> m ? m : Map.of();
        BigDecimal lat = decimal(location.get("latitude")), lng = decimal(location.get("longitude"));
        URI uri = uri(map.get("googleMapsUri"));
        List<String> days = new ArrayList<>(); Boolean open = null;
        if (map.get("regularOpeningHours") instanceof Map<?, ?> hours) {
            if (hours.get("weekdayDescriptions") instanceof List<?> values) values.forEach(v -> { if (v instanceof String s) days.add(s); });
            if (hours.get("openNow") instanceof Boolean b) open = b;
        }
        List<?> types = map.get("types") instanceof List<?> t ? t : List.of();
        ParkingType parking = types.stream().anyMatch(t -> String.valueOf(t).toLowerCase(Locale.ROOT).contains("parking")) ? ParkingType.UNKNOWN : ParkingType.UNKNOWN;
        return new PlaceDetails(id, name, address, city(address), district(address), lat, lng, uri,
                days.isEmpty() && open == null ? null : new OpeningHoursData(open, List.copyOf(days), null), parking);
    }
    private static String city(String address) { if (address == null) return null; String s=address.toLowerCase(Locale.ROOT); return s.contains("hà nội")||s.contains("ha noi")?"HN":s.contains("hồ chí minh")||s.contains("ho chi minh")||s.contains("sài gòn")?"HCM":s.contains("đà nẵng")||s.contains("da nang")?"DN":null; }
    private static String district(String address) { if(address==null)return null; for(String part:address.split(",")){String p=part.strip(); if(p.toLowerCase(Locale.ROOT).matches("(quận|huyện|phường|q\\.|h\\.).*"))return p;} return null; }
    private static String nestedString(Object value,String key) { return value instanceof Map<?,?> m ? string(m.get(key)) : null; }
    private static String string(Object value) { return value instanceof String s ? s : null; }
    private static BigDecimal decimal(Object value) { try { return value == null ? null : new BigDecimal(value.toString()); } catch(NumberFormatException e) { return null; } }
    private static URI uri(Object value) { try { return value == null ? null : URI.create(value.toString()); } catch(IllegalArgumentException e) { return null; } }
}
