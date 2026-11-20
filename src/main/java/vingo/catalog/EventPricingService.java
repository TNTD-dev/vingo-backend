package vingo.catalog;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vingo.catalog.internal.EventPricingRepository;
import vingo.catalog.internal.EventRepository;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;
import vingo.shared.security.CurrentUser;

@Service
public class EventPricingService {

    private static final Logger log = LoggerFactory.getLogger(EventPricingService.class);
    private static final String[] PALETTE = {"#86BDF6", "#A4CC83", "#F5BF70", "#B6A1E5", "#F49CAB", "#70C8C1"};

    private final EventRepository events;
    private final EventPricingRepository pricing;
    private final ApplicationEventPublisher publisher;
    private final CurrentUser currentUser;
    private final Clock clock;

    public EventPricingService(
            EventRepository events,
            EventPricingRepository pricing,
            ApplicationEventPublisher publisher,
            CurrentUser currentUser,
            Clock clock
    ) {
        this.events = events;
        this.pricing = pricing;
        this.publisher = publisher;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Transactional
    public void putPriceTiers(UUID eventId, List<PriceTierChange> input) {
        OffsetDateTime now = lockEditableEvent(eventId);
        UUID actorId = currentUser.require().id();

        if (input == null) {
            throw invalid("Thiếu danh sách hạng giá.");
        }

        var existing = events.findPriceTiers(eventId, now);

        Map<UUID, EventDetails.Tier> byId = new HashMap<>();
        Map<String, EventDetails.Tier> byName = new HashMap<>();
        existing.forEach(tier -> {
            byId.put(tier.id(), tier);
            byName.put(tier.name(), tier);
        });

        var normalized = new ArrayList<PriceTierChange>();
        Set<String> names = new HashSet<>();
        Set<UUID> retainedIds = new HashSet<>();

        for (PriceTierChange item : input) {
            validateTier(item);
            String name = item.name().strip();
            if (!names.add(name)) {
                throw invalid("Tên hạng giá bị trùng: " + name);
            }
            UUID id = item.id();
            if (id != null) {
                if (!byId.containsKey(id)) {
                    throw invalid("Hạng giá không thuộc sự kiện.");
                }
            } else {
                var sameName = byName.get(name);
                if (sameName != null) {
                    id = sameName.id();
                }
            }
            if (id != null && !retainedIds.add(id)) {
                throw invalid("Một hạng giá xuất hiện nhiều lần.");
            }
            String color = item.color();
            if (color == null && id != null) {
                color = byId.get(id).color();
            }
            if (color == null) {
                color = PALETTE[Math.floorMod(Long.hashCode(item.price()), PALETTE.length)];
            }
            normalized.add(new PriceTierChange(id, name, item.price(), color));
        }

        var removed = existing.stream()
                .filter(tier -> !retainedIds.contains(tier.id()))
                .toList();

        for (var tier : removed) {
            if (pricing.isTierReferenced(tier.id())) {
                throw invalid("Không thể xóa hạng giá đang được khu hoặc dòng đơn hàng sử dụng: " + tier.name());
            }
        }

        for (var tier : removed) {
            pricing.deleteTier(eventId, tier.id());
        }

        Set<String> reservedNames = new HashSet<>(names);
        existing.forEach(tier -> reservedNames.add(tier.name()));

        for (var item : normalized) {
            if (item.id() != null && !byId.get(item.id()).name().equals(item.name())) {
                pricing.renameTierTemporarily(eventId, item.id(), temporaryName(item.id(), reservedNames));
            }
        }

        for (var item : normalized) {
            if (item.id() == null) {
                pricing.insertTier(eventId, item, actorId, now);
            } else {
                pricing.updateTier(eventId, item, actorId, now);
            }
        }

        finishChange(eventId, now, "Đã cập nhật hạng giá");
    }

    /** Thêm phiên bản giá mới (đổi giá ngay hoặc hẹn giờ tương lai); cho phép cả sau khi mở bán. */
    @Transactional
    public EventDetails.SkuPriceRow addSkuPrice(
            UUID eventId,
            UUID skuId,
            long price,
            OffsetDateTime effectiveFrom,
            String reason
    ) {
        if (price < 0) {
            throw invalid("Giá vé phải là số nguyên không âm.");
        }
        var event = events.lockById(eventId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy sự kiện."));
        if ("CANCELLED".equals(event.status())) {
            throw new DomainException(ErrorCode.EVENT_LOCKED, "Không thể đổi giá cho sự kiện đã hủy.");
        }
        if (!pricing.skuExistsInEvent(eventId, skuId)) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy hạng vé trong sự kiện.");
        }

        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        OffsetDateTime effective = effectiveFrom == null ? now : effectiveFrom;
        if (pricing.skuPriceTimestampExists(skuId, effective)) {
            throw invalid("Đã có mức giá ở mốc thời gian hiệu lực này.");
        }
        UUID actorId = currentUser.require().id();
        String normalizedReason = reason == null || reason.isBlank() ? null : reason.strip();
        var created = pricing.insertSkuPrice(skuId, price, effective, actorId, normalizedReason, now);
        finishChange(eventId, now, "Đã thêm phiên bản giá mới");
        return created;
    }

    private OffsetDateTime lockEditableEvent(UUID eventId) {
        var event = events.lockById(eventId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Không tìm thấy sự kiện."));

        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        if (!Set.of("DRAFT", "PUBLISHED").contains(event.status())
                || pricing.hasStartedSalesOrOrders(eventId, now)) {
            throw new DomainException(ErrorCode.EVENT_LOCKED,
                    "Sự kiện đã bắt đầu bán, có đơn hoặc không còn cho chỉnh sửa.");
        }
        return now;
    }

    private static void validateTier(PriceTierChange item) {
        if (item == null) {
            throw invalid("Danh sách không được chứa phần tử null.");
        }
        if (item.name() == null || item.name().isBlank() || item.name().strip().length() > 100) {
            throw invalid("Tên hạng giá không được trắng và tối đa 100 ký tự.");
        }
        if (item.price() == null || item.price() < 0) {
            throw invalid("Giá vé phải là số nguyên không âm.");
        }
        if (item.color() != null && !item.color().matches("^#[0-9A-Fa-f]{6}$")) {
            throw invalid("Màu phải có dạng #RRGGBB.");
        }
    }

    private static String temporaryName(UUID id, Set<String> reserved) {
        int suffix = 0;
        while (true) {
            String candidate = "__EV4_" + id.toString().substring(0, 8) + "_" + suffix++;
            if (reserved.add(candidate)) {
                return candidate;
            }
        }
    }

    private void finishChange(UUID eventId, OffsetDateTime now, String message) {
        pricing.touchEvent(eventId, now);
        publisher.publishEvent(new CatalogChanged(eventId, events.findShowtimeIds(eventId)));
        try (var ignored = MDC.putCloseable("event_id", eventId.toString())) {
            log.info(message);
        }
    }

    private static DomainException invalid(String message) {
        return new DomainException(ErrorCode.VALIDATION_ERROR, message);
    }

    @Transactional
    public void putZonePrices(UUID eventId, List<ZonePriceChange> input) {
        OffsetDateTime now = lockEditableEvent(eventId);
        if (input == null) {
            throw invalid("Thiếu danh sách giá khu.");
        }

        UUID seatMapId = pricing.findEventSeatMapId(eventId).orElse(null);
        if (seatMapId == null) {
            if (input.isEmpty()) {
                pricing.replaceZonePrices(eventId, null, List.of());
                finishChange(eventId, now, "Đã xóa giá khu");
                return;
            }
            throw invalid("Sự kiện chưa chọn sơ đồ ghế.");
        }

        Set<UUID> allowedZones = pricing.findUsedZoneIds(eventId);
        Set<UUID> allowedTiers = new HashSet<>();
        events.findPriceTiers(eventId, now).forEach(tier -> allowedTiers.add(tier.id()));

        Set<UUID> submittedZones = new HashSet<>();
        for (var item : input) {
            if (item == null || item.zoneId() == null || item.priceTierId() == null) {
                throw invalid("Mỗi dòng phải có zone_id và price_tier_id.");
            }
            if (item.displayName() != null
                    && (item.displayName().isBlank() || item.displayName().strip().length() > 200)) {
                throw invalid("Tên khu phải có từ 1 đến 200 ký tự.");
            }
            if (!submittedZones.add(item.zoneId())) {
                throw invalid("Một khu xuất hiện nhiều lần.");
            }
            if (!allowedZones.contains(item.zoneId())) {
                throw invalid("Khu không thuộc sơ đồ của sự kiện.");
            }
            if (!allowedTiers.contains(item.priceTierId())) {
                throw invalid("Hạng giá không thuộc sự kiện.");
            }
        }

        pricing.replaceZonePrices(eventId, seatMapId, input);
        finishChange(eventId, now, "Đã cập nhật giá cho khu");
    }
}
