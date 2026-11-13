package vingo.auth;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vingo.api.model.BuyerProfile;
import vingo.auth.internal.BuyerProfileRepository;
import vingo.shared.security.CurrentUser;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

@Service
public class BuyerProfileService {
    private final BuyerProfileRepository profiles;
    private final CurrentUser currentUser;
    private final Clock clock;
    public BuyerProfileService(BuyerProfileRepository profiles, CurrentUser currentUser, Clock clock) {
        this.profiles = profiles; this.currentUser = currentUser; this.clock = clock;
    }
    @Transactional(readOnly = true)
    public BuyerProfile get() {
        return profiles.find(currentUser.require().id()).orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Buyer profile not found"));
    }
    @Transactional
    public BuyerProfile put(BuyerProfile input) {
        var user = currentUser.require();
        if (input == null || !valid(input.getFullName(), 200) || !valid(input.getPhone(), 32)
                || !valid(input.getEmail(), 254) || !input.getEmail().trim().matches("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+")) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Full name, phone and valid email are required");
        }
        var profile = new BuyerProfile(input.getFullName().trim(), input.getPhone().trim(), input.getEmail().trim());
        profiles.save(user.id(), profile, OffsetDateTime.now(clock));
        return profile;
    }
    private static boolean valid(String value, int limit) { return value != null && !value.isBlank() && value.length() <= limit; }
}
