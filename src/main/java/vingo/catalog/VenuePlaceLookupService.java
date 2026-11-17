package vingo.catalog;

import java.math.BigDecimal;
import java.net.URI;
import java.text.Normalizer;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import vingo.catalog.internal.GeminiParkingFeeClient;
import vingo.catalog.internal.GeminiParkingFeeClient.GroundedParkingFee;
import vingo.catalog.internal.GeminiParkingFeeClient.WebCitation;
import vingo.catalog.internal.GooglePlacesClient;
import vingo.catalog.internal.GooglePlacesClient.OpeningHoursData;
import vingo.catalog.internal.GooglePlacesClient.ParkingType;
import vingo.catalog.internal.GooglePlacesClient.PlaceDetails;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

/**
 * Tra cứu địa chỉ thật, giờ hoạt động của sân và bãi đỗ xe lân cận qua Google API ở backend.
 * Các phương thức không mở transaction để tránh giữ kết nối DB khi gọi HTTP ra ngoài.
 */
@Service
public class VenuePlaceLookupService {

    public static final String UNVERIFIED_FEE_TEXT = "chưa xác minh";
    private static final int DEFAULT_SEARCH_LIMIT = 5;
    private static final int DEFAULT_RADIUS_METERS = 500;
    private static final int NEARBY_PARKING_LIMIT = 10;
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private final GooglePlacesClient placesClient;
    private final GeminiParkingFeeClient geminiParkingFeeClient;
    private final Clock clock;

    public VenuePlaceLookupService(
            GooglePlacesClient placesClient,
            GeminiParkingFeeClient geminiParkingFeeClient,
            Clock clock
    ) {
        this.placesClient = placesClient;
        this.geminiParkingFeeClient = geminiParkingFeeClient;
        this.clock = clock;
    }

    public enum Confidence {
        HIGH,
        MEDIUM,
        LOW,
        UNVERIFIED
    }

    public enum SourceType {
        GOOGLE_PLACES,
        WEB_GROUNDING,
        MANUAL
    }

    public record PlaceCandidate(
            String placeId,
            String name,
            String formattedAddress,
            String cityCode,
            String district,
            BigDecimal lat,
            BigDecimal lng,
            URI googleMapsUri
    ) {}

    public record LookupCommand(
            String placeId,
            BigDecimal lat,
            BigDecimal lng,
            String venueName,
            Integer radiusMeters
    ) {}

    public record OpeningHours(
            Boolean openNow,
            List<String> weekdayDescriptions,
            String summary
    ) {}

    public record Source(
            SourceType type,
            String title,
            URI uri,
            String snippet
    ) {}

    public record ParkingLot(
            String placeId,
            String name,
            String address,
            BigDecimal lat,
            BigDecimal lng,
            Integer distanceMeters,
            OpeningHours openingHours,
            ParkingType parkingType,
            String feeText,
            boolean feeVerified,
            Confidence confidence,
            List<Source> sources
    ) {}

    public record LookupResult(
            String placeId,
            String venueName,
            String formattedAddress,
            String cityCode,
            String district,
            BigDecimal lat,
            BigDecimal lng,
            OpeningHours venueOpeningHours,
            List<ParkingLot> parkingLots,
            String openingHoursSummary,
            String parkingSummary,
            List<Source> sources,
            OffsetDateTime lookedUpAt,
            Confidence confidence
    ) {}

    public List<PlaceCandidate> searchPlaces(String q, String cityCode, Integer limit) {
        if (q == null || q.isBlank()) {
            throw invalid("Từ khóa tìm kiếm địa chỉ không được để trống.");
        }
        int maxResults = limit == null ? DEFAULT_SEARCH_LIMIT : limit;
        if (maxResults < 1 || maxResults > 20) {
            throw invalid("limit phải nằm trong khoảng 1 đến 20.");
        }

        String textQuery = appendCityHint(q.strip(), cityCode);
        List<PlaceDetails> places = placesClient.searchText(textQuery, maxResults);

        List<PlaceCandidate> candidates = new ArrayList<>();
        for (PlaceDetails place : places) {
            if (place.placeId() == null || place.lat() == null || place.lng() == null) {
                continue;
            }
            String displayName = place.name() != null ? place.name() : place.formattedAddress();
            String formattedAddress = place.formattedAddress() != null ? place.formattedAddress() : displayName;
            if (displayName == null || formattedAddress == null) {
                continue;
            }
            candidates.add(new PlaceCandidate(
                    place.placeId(),
                    displayName,
                    formattedAddress,
                    place.cityCode(),
                    place.district(),
                    place.lat(),
                    place.lng(),
                    place.googleMapsUri()
            ));
        }
        return List.copyOf(candidates);
    }

    public LookupResult lookupPlaceInfo(LookupCommand command) {
        if (command == null) {
            throw invalid("Yêu cầu tra cứu không được để trống.");
        }

        int radiusMeters = command.radiusMeters() == null ? DEFAULT_RADIUS_METERS : command.radiusMeters();
        if (radiusMeters < 50 || radiusMeters > 5000) {
            throw invalid("radius_meters phải nằm trong khoảng 50 đến 5000.");
        }

        boolean hasLat = command.lat() != null;
        boolean hasLng = command.lng() != null;
        if (hasLat != hasLng) {
            throw invalid("Cần cung cấp đủ cả lat và lng.");
        }
        if (hasLat && (command.lat().abs().compareTo(BigDecimal.valueOf(90)) > 0
                || command.lng().abs().compareTo(BigDecimal.valueOf(180)) > 0)) {
            throw invalid("Tọa độ tra cứu nằm ngoài phạm vi hợp lệ.");
        }

        String normalizedPlaceId = normalizeBlank(command.placeId());
        if (normalizedPlaceId == null && !hasLat) {
            throw invalid("Cần cung cấp place_id hoặc tọa độ lat, lng.");
        }

        PlaceDetails venuePlace = normalizedPlaceId != null
                ? placesClient.getPlaceDetails(normalizedPlaceId)
                : null;

        BigDecimal searchLat = hasLat ? command.lat() : (venuePlace != null ? venuePlace.lat() : null);
        BigDecimal searchLng = hasLat ? command.lng() : (venuePlace != null ? venuePlace.lng() : null);

        List<PlaceDetails> nearbyPlaces = (searchLat != null && searchLng != null)
                ? placesClient.searchNearbyParking(searchLat, searchLng, radiusMeters, NEARBY_PARKING_LIMIT)
                : List.of();

        String resolvedPlaceId = venuePlace != null && venuePlace.placeId() != null
                ? venuePlace.placeId()
                : normalizedPlaceId;
        String resolvedVenueName = firstNonBlank(
                venuePlace != null ? venuePlace.name() : null,
                normalizeBlank(command.venueName())
        );
        String formattedAddress = venuePlace != null ? venuePlace.formattedAddress() : null;
        String cityCode = venuePlace != null ? venuePlace.cityCode() : null;
        String district = venuePlace != null ? venuePlace.district() : null;
        BigDecimal venueLat = venuePlace != null && venuePlace.lat() != null ? venuePlace.lat() : searchLat;
        BigDecimal venueLng = venuePlace != null && venuePlace.lng() != null ? venuePlace.lng() : searchLng;
        OpeningHours venueOpeningHours = toOpeningHours(venuePlace != null ? venuePlace.openingHours() : null);

        List<PlaceDetails> deduplicatedLots = deduplicateByPlaceId(nearbyPlaces);
        List<String> lotNames = deduplicatedLots.stream()
                .map(PlaceDetails::name)
                .filter(name -> name != null && !name.isBlank())
                .toList();

        Optional<GroundedParkingFee> groundedFee = geminiParkingFeeClient.lookupParkingFee(
                resolvedVenueName,
                formattedAddress,
                lotNames
        );

        List<Source> webSources = groundedFee.map(fee -> fee.sources().stream()
                        .map(VenuePlaceLookupService::toWebSource)
                        .toList())
                .orElse(List.of());

        List<ParkingLot> parkingLots = buildParkingLots(
                deduplicatedLots,
                searchLat,
                searchLng,
                venuePlace,
                groundedFee.orElse(null),
                webSources
        );

        Map<String, Source> aggregatedSources = new LinkedHashMap<>();
        if (venuePlace != null) {
            Source venueSource = toGooglePlaceSource(
                    resolvedVenueName != null ? resolvedVenueName : "Thông tin địa điểm sân",
                    venuePlace.googleMapsUri(),
                    formattedAddress
            );
            putSource(aggregatedSources, venueSource);
        }
        for (ParkingLot lot : parkingLots) {
            for (Source source : lot.sources()) {
                putSource(aggregatedSources, source);
            }
        }
        if (parkingLots.isEmpty() && groundedFee.isPresent() && groundedFee.get().venueFeeText() != null) {
            for (Source webSource : webSources) {
                putSource(aggregatedSources, webSource);
            }
        }

        OffsetDateTime lookedUpAt = clock.instant().atOffset(ZoneOffset.UTC);
        Confidence overallConfidence = computeOverallConfidence(
                venuePlace,
                venueOpeningHours,
                parkingLots,
                groundedFee.orElse(null)
        );
        String openingHoursSummary = venueOpeningHours != null ? venueOpeningHours.summary() : null;
        String parkingSummary = buildParkingSummary(parkingLots, groundedFee.orElse(null), radiusMeters);

        return new LookupResult(
                resolvedPlaceId,
                resolvedVenueName,
                formattedAddress,
                cityCode,
                district,
                venueLat,
                venueLng,
                venueOpeningHours,
                parkingLots,
                openingHoursSummary,
                parkingSummary,
                List.copyOf(aggregatedSources.values()),
                lookedUpAt,
                overallConfidence
        );
    }

    private static List<ParkingLot> buildParkingLots(
            List<PlaceDetails> nearbyPlaces,
            BigDecimal originLat,
            BigDecimal originLng,
            PlaceDetails venuePlace,
            GroundedParkingFee groundedFee,
            List<Source> webSources
    ) {
        List<ParkingLot> lots = new ArrayList<>();
        for (PlaceDetails place : nearbyPlaces) {
            String lotName = firstNonBlank(place.name(), place.formattedAddress());
            if (lotName == null) {
                continue;
            }
            Integer distanceMeters = computeDistanceMeters(originLat, originLng, place.lat(), place.lng());
            OpeningHours lotOpeningHours = toOpeningHours(place.openingHours());

            ParkingType parkingType = place.parkingType();
            if (parkingType == ParkingType.UNKNOWN && venuePlace != null
                    && venuePlace.parkingType() != ParkingType.UNKNOWN
                    && distanceMeters != null && distanceMeters <= 150) {
                parkingType = venuePlace.parkingType();
            }

            String matchedFee = matchLotFee(lotName, distanceMeters, groundedFee);
            boolean feeVerified = matchedFee != null && !webSources.isEmpty();
            String feeText = feeVerified ? matchedFee : UNVERIFIED_FEE_TEXT;

            List<Source> lotSources = new ArrayList<>();
            lotSources.add(toGooglePlaceSource(lotName, place.googleMapsUri(), place.formattedAddress()));
            if (feeVerified) {
                lotSources.addAll(webSources);
            }

            Confidence lotConfidence;
            if (feeVerified) {
                lotConfidence = Confidence.HIGH;
            } else if (lotOpeningHours != null || parkingType != ParkingType.UNKNOWN) {
                lotConfidence = Confidence.MEDIUM;
            } else {
                lotConfidence = Confidence.LOW;
            }

            lots.add(new ParkingLot(
                    place.placeId(),
                    lotName,
                    place.formattedAddress(),
                    place.lat(),
                    place.lng(),
                    distanceMeters,
                    lotOpeningHours,
                    parkingType,
                    feeText,
                    feeVerified,
                    lotConfidence,
                    List.copyOf(lotSources)
            ));
        }
        return List.copyOf(lots);
    }

    private static String matchLotFee(String lotName, Integer distanceMeters, GroundedParkingFee groundedFee) {
        if (groundedFee == null || groundedFee.sources().isEmpty()) {
            return null;
        }
        String normalizedLot = lotName.strip().toLowerCase(Locale.ROOT);
        for (var entry : groundedFee.lotFeesByName().entrySet()) {
            String key = entry.getKey();
            if (normalizedLot.equals(key)
                    || (key.length() >= 4 && (normalizedLot.contains(key) || key.contains(normalizedLot)))) {
                return entry.getValue();
            }
        }
        if (groundedFee.venueFeeText() != null && distanceMeters != null && distanceMeters <= 200) {
            return groundedFee.venueFeeText();
        }
        return null;
    }

    private static Confidence computeOverallConfidence(
            PlaceDetails venuePlace,
            OpeningHours venueOpeningHours,
            List<ParkingLot> parkingLots,
            GroundedParkingFee groundedFee
    ) {
        boolean anyFeeVerified = parkingLots.stream().anyMatch(ParkingLot::feeVerified)
                || (groundedFee != null && groundedFee.venueFeeText() != null && !groundedFee.sources().isEmpty());
        if ((venuePlace != null || !parkingLots.isEmpty()) && anyFeeVerified) {
            return Confidence.HIGH;
        }
        if (venueOpeningHours != null || !parkingLots.isEmpty()) {
            return Confidence.MEDIUM;
        }
        if (venuePlace != null) {
            return Confidence.LOW;
        }
        return Confidence.LOW;
    }

    private static String buildParkingSummary(
            List<ParkingLot> parkingLots,
            GroundedParkingFee groundedFee,
            int radiusMeters
    ) {
        if (parkingLots.isEmpty()) {
            if (groundedFee != null && groundedFee.venueFeeText() != null && !groundedFee.sources().isEmpty()) {
                return "Chưa thấy bãi xe riêng trong bán kính " + radiusMeters
                        + "m trên Google Places; phí tham khảo theo nguồn trích dẫn: " + groundedFee.venueFeeText() + ".";
            }
            return "Chưa tìm thấy bãi xe lân cận trong bán kính " + radiusMeters
                    + "m; mức phí: " + UNVERIFIED_FEE_TEXT + ".";
        }

        ParkingLot nearest = parkingLots.getFirst();
        StringBuilder sb = new StringBuilder();
        sb.append("Tìm thấy ").append(parkingLots.size()).append(" bãi xe lân cận. Gần nhất: ")
                .append(nearest.name());
        if (nearest.distanceMeters() != null) {
            sb.append(" (").append(nearest.distanceMeters()).append("m)");
        }
        sb.append(", loại: ").append(describeParkingType(nearest.parkingType()));
        sb.append(", phí: ").append(nearest.feeText());
        return sb.toString();
    }

    private static String describeParkingType(ParkingType type) {
        return switch (type) {
            case FREE -> "miễn phí";
            case PAID -> "có thu phí";
            case FREE_AND_PAID -> "có khu miễn phí và khu thu phí";
            case UNKNOWN -> "chưa rõ hình thức thu phí";
        };
    }

    private static List<PlaceDetails> deduplicateByPlaceId(List<PlaceDetails> places) {
        Map<String, PlaceDetails> unique = new LinkedHashMap<>();
        int anonIndex = 0;
        for (PlaceDetails place : places) {
            String key = place.placeId() != null ? place.placeId() : "anon-" + (++anonIndex);
            unique.putIfAbsent(key, place);
        }
        return List.copyOf(unique.values());
    }

    private static OpeningHours toOpeningHours(OpeningHoursData data) {
        if (data == null) {
            return null;
        }
        return new OpeningHours(data.openNow(), data.weekdayDescriptions(), data.summary());
    }

    private static Source toGooglePlaceSource(String title, URI uri, String snippet) {
        return new Source(SourceType.GOOGLE_PLACES, title, uri, snippet);
    }

    private static Source toWebSource(WebCitation citation) {
        return new Source(SourceType.WEB_GROUNDING, citation.title(), citation.uri(), citation.snippet());
    }

    private static void putSource(Map<String, Source> map, Source source) {
        String key = source.type() + "|" + (source.uri() != null ? source.uri().toString() : source.title());
        map.putIfAbsent(key, source);
    }

    private static Integer computeDistanceMeters(
            BigDecimal lat1,
            BigDecimal lng1,
            BigDecimal lat2,
            BigDecimal lng2
    ) {
        if (lat1 == null || lng1 == null || lat2 == null || lng2 == null) {
            return null;
        }
        double phi1 = Math.toRadians(lat1.doubleValue());
        double phi2 = Math.toRadians(lat2.doubleValue());
        double deltaPhi = Math.toRadians(lat2.doubleValue() - lat1.doubleValue());
        double deltaLambda = Math.toRadians(lng2.doubleValue() - lng1.doubleValue());

        double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return (int) Math.round(EARTH_RADIUS_METERS * c);
    }

    private static String appendCityHint(String query, String cityCode) {
        if (cityCode == null || cityCode.isBlank()) {
            return query;
        }
        String cityLabel = switch (cityCode) {
            case "HN" -> "Hà Nội";
            case "HCM" -> "TP. Hồ Chí Minh";
            case "DN" -> "Đà Nẵng";
            default -> null;
        };
        if (cityLabel == null) {
            return query;
        }
        String normalizedQuery = normalizeAscii(query);
        boolean alreadyHasCity = switch (cityCode) {
            case "HN" -> normalizedQuery.contains("ha noi") || normalizedQuery.contains("hanoi");
            case "HCM" -> normalizedQuery.contains("ho chi minh")
                    || normalizedQuery.contains("sai gon")
                    || normalizedQuery.contains("hcmc");
            case "DN" -> normalizedQuery.contains("da nang") || normalizedQuery.contains("danang");
            default -> false;
        };
        return alreadyHasCity ? query : query + ", " + cityLabel;
    }

    private static String normalizeAscii(String input) {
        String decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replace('đ', 'd')
                .replace('Đ', 'D');
        return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    private static String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.strip();
        }
        return fallback != null && !fallback.isBlank() ? fallback.strip() : null;
    }

    private static DomainException invalid(String message) {
        return new DomainException(ErrorCode.VALIDATION_ERROR, message);
    }
}
