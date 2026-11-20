package vingo.catalog.web;

import java.math.BigDecimal;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import vingo.api.VenueSetupApi;
import vingo.api.model.LocationSuggestion;
import vingo.api.model.SuggestVenueLocations200Response;
import vingo.catalog.internal.VenueSetupClients;

@RestController
@PreAuthorize("hasRole('ADMIN')")
class VenueSetupController implements VenueSetupApi {
    private final VenueSetupClients locations;

    VenueSetupController(VenueSetupClients locations) {
        this.locations = locations;
    }

    @Override
    public ResponseEntity<SuggestVenueLocations200Response> suggestVenueLocations(String q, Integer limit) {
        return ResponseEntity.ok(new SuggestVenueLocations200Response().data(locations.suggest(q, limit == null ? 5 : limit)));
    }

    @Override
    public ResponseEntity<LocationSuggestion> reverseGeocodeVenueLocation(BigDecimal lat, BigDecimal lng) {
        return ResponseEntity.ok(locations.reverse(lat, lng));
    }
}
