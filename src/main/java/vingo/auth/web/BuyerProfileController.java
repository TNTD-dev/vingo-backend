package vingo.auth.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import vingo.api.MeApi;
import vingo.api.model.BuyerProfile;
import vingo.auth.BuyerProfileService;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class BuyerProfileController implements MeApi {
    private final BuyerProfileService profiles;
    BuyerProfileController(BuyerProfileService profiles) { this.profiles = profiles; }
    @Override public ResponseEntity<BuyerProfile> getBuyerProfile() { return ResponseEntity.ok(profiles.get()); }
    @Override public ResponseEntity<BuyerProfile> putBuyerProfile(BuyerProfile body) { return ResponseEntity.ok(profiles.put(body)); }
}
