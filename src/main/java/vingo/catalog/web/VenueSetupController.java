package vingo.catalog.web;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.RestController;
import vingo.api.VenueSetupApi;
import vingo.api.model.*;
import vingo.catalog.VenueSetupService;

@RestController
class VenueSetupController implements VenueSetupApi {
    private final VenueSetupService setup;
    VenueSetupController(VenueSetupService setup){this.setup=setup;}
    @Override public ResponseEntity<SuggestVenueLocations200Response> suggestVenueLocations(String q,Integer limit){return ResponseEntity.ok(new SuggestVenueLocations200Response().data(setup.suggest(q,limit==null?5:limit)));}
    @Override
    public ResponseEntity<FindNearbyParkingResponse> findNearbyParking(BigDecimal lat, BigDecimal lng, Integer limit) {
        return ResponseEntity.ok(new FindNearbyParkingResponse().data(setup.nearbyParking(lat, lng, limit == null ? 5 : limit)));
    }

    @Override public ResponseEntity<LocationSuggestion> reverseGeocodeVenueLocation(BigDecimal lat,BigDecimal lng){return ResponseEntity.ok(setup.reverse(lat,lng));}
    @Override public ResponseEntity<VenueImportSession> generateImportParkingGuidance(UUID id,ImportParkingGuidanceRequest body){return ResponseEntity.ok(setup.generate(id,body));}
}
