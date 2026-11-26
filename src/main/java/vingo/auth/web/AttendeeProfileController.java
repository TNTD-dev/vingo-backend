package vingo.auth.web;

import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vingo.api.AttendeesApi;
import vingo.api.model.AttendeeProfile;
import vingo.api.model.AttendeeRequest;
import vingo.auth.AttendeeProfileService;
import vingo.shared.security.CurrentUser;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class AttendeeProfileController implements AttendeesApi {
    private final AttendeeProfileService profiles;
    private final CurrentUser currentUser;

    AttendeeProfileController(AttendeeProfileService profiles, CurrentUser currentUser) {
        this.profiles = profiles;
        this.currentUser = currentUser;
    }

    @Override
    public ResponseEntity<List<AttendeeProfile>> listAttendees() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.list(currentUser.require().id()));
    }

    @Override
    public ResponseEntity<AttendeeProfile> createAttendee(AttendeeRequest body) {
        var profile = profiles.create(currentUser.require().id(), body);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(profile);
    }

    @Override
    public ResponseEntity<AttendeeProfile> getAttendee(UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.get(currentUser.require().id(), id));
    }

    @Override
    public ResponseEntity<AttendeeProfile> replaceAttendee(UUID id, AttendeeRequest body) {
        var profile = profiles.replace(currentUser.require().id(), id, body);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profile);
    }

    @Override
    public ResponseEntity<Void> deleteAttendee(UUID id) {
        profiles.delete(currentUser.require().id(), id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
