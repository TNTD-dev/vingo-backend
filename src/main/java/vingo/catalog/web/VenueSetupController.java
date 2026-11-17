package vingo.catalog.web;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vingo.api.VenueSetupApi;
import vingo.api.model.*;
import vingo.catalog.VenueSetupService;
import vingo.shared.error.*;

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
    @Override public ResponseEntity<VenueRulesDocument> getImportRulesDocument(UUID id){return ResponseEntity.ok(setup.document(id));}
    @Override public ResponseEntity<Resource> downloadImportRulesDocument(UUID id){return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header("Content-Disposition","inline; filename=rules.pdf").body(new ByteArrayResource(setup.download(id)));}
    @Override public ResponseEntity<VenueRulesDocument> uploadImportRulesDocument(UUID id,Long revision,MultipartFile file){
        try{return ResponseEntity.ok(setup.upload(id,revision,file.getOriginalFilename(),file.getContentType(),file.getBytes()));}
        catch(IOException ex){throw new DomainException(ErrorCode.VALIDATION_ERROR,"Không đọc được PDF.");}
    }
}
