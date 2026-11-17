package vingo.catalog.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vingo.api.VenueImportsApi;
import vingo.api.model.*;
import vingo.catalog.VenueImportService;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

@RestController
class VenueImportController implements VenueImportsApi {
    private final VenueImportService service;
    VenueImportController(VenueImportService service) {this.service=service;}
    @Override public ResponseEntity<VenueImportSession> createVenueImport(VenueImportCreate body) {return ResponseEntity.status(HttpStatus.CREATED).body(service.create(body));}
    @Override public ResponseEntity<VenueImportPage> listVenueImports(String cursor,Integer limit) {return ResponseEntity.ok(service.list(cursor,limit));}
    @Override public ResponseEntity<VenueImportSession> getVenueImport(UUID id) {return ResponseEntity.ok(service.get(id));}
    @Override public ResponseEntity<VenueImportSession> updateVenueImport(UUID id,VenueImportPatch body) {return ResponseEntity.ok(service.patch(id,body));}
    @Override public ResponseEntity<VenueImportSession> deleteVenueImportFile(Long revision,UUID id,UUID file) {return ResponseEntity.ok(service.deleteFile(id,file,revision));}
    @Override public ResponseEntity<VenueImportPreview> previewVenueImport(UUID id,ImportRevision body) {return ResponseEntity.ok(service.preview(id,body.getRevision()));}
    @Override public ResponseEntity<VenueImportResult> completeVenueImport(UUID id,ImportRevision body) {return ResponseEntity.ok(service.complete(id,body.getRevision()));}
    @Override public ResponseEntity<VenueImportSession> cancelVenueImport(UUID id,ImportRevision body) {return ResponseEntity.ok(service.cancel(id,body.getRevision()));}
    @Override public ResponseEntity<VenueOverview> getVenueOverview(UUID id) {return ResponseEntity.ok(service.getOverview(id));}
    @Override public ResponseEntity<VenueOverviewPage> listVenueOverviews(UUID venue,String cursor,Integer limit) {return ResponseEntity.ok(service.listOverviews(venue,cursor,limit));}
    @Override public ResponseEntity<VenueImportSession> uploadVenueImportFiles(UUID id,Long revision,List<MultipartFile> files) {
        if(files==null||files.isEmpty()) throw new DomainException(ErrorCode.VALIDATION_ERROR,"Cần file SVG.");
        if(files.size()>VenueImportService.FILE_COUNT) throw new DomainException(ErrorCode.PAYLOAD_TOO_LARGE,"Mỗi request tối đa 32 file.");
        long total=0;
        List<VenueImportService.Upload> uploads=new ArrayList<>();
        for(var file:files) {
            try {
                if(file.getContentType()==null||!MediaType.parseMediaType("image/svg+xml").equalsTypeAndSubtype(MediaType.parseMediaType(file.getContentType())))
                    throw new DomainException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,"Chỉ nhận image/svg+xml.");
            } catch(org.springframework.http.InvalidMediaTypeException ex) {throw new DomainException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,"Kiểu file không hợp lệ.");}
            total+=file.getSize();
            if(total>VenueImportService.FILE_LIMIT) throw new DomainException(ErrorCode.PAYLOAD_TOO_LARGE,"Mỗi request tối đa 10 MiB.");
            try {uploads.add(new VenueImportService.Upload(file.getOriginalFilename(),file.getBytes()));}
            catch(IOException ex) {throw new DomainException(ErrorCode.VALIDATION_ERROR,"Không đọc được file SVG.");}
        }
        return ResponseEntity.ok(service.upload(id,revision,uploads));
    }
}
