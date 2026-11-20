package vingo.catalog.web;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import com.jayway.jsonpath.JsonPath;
import vingo.catalog.VenueImportCleanupService;
import vingo.shared.security.Role;
import vingo.support.IntegrationTest;
import vingo.support.VenueImportHttpFixture;
import static vingo.support.VenueImportHttpFixture.*;

class VenueImportIntegrationTest extends IntegrationTest {
    private static final String BASE=svg("<rect id='GATE__G1' x='90' y='90' width='2' height='2'/><rect id='AREA__A' width='50' height='50'/>");
    private static final String DETAIL=svg("<rect id='ZONE__A__STANDING__10' width='20' height='20'/>");
    @Autowired VenueImportCleanupService cleanup;
    @Autowired vingo.catalog.VenueImportService applicationImports;
    @AfterEach void resetClock(){clock.reset();}
    @Test void createsResumableDraftCompletesAtomicallyAndRetryReturnsSameIds() {
        String token=tokenFor(Role.ADMIN);String state=create(client,token,null);UUID id=id(state);
        state=upload(client,token,id,revision(state),bytes(BASE),"image/svg+xml");
        state=upload(client,token,id,revision(state),bytes(DETAIL),"image/svg+xml");
        assertThat(jdbc.sql("SELECT count(*) FROM venue_import_file WHERE session_id=:id").param("id",id).query(Integer.class).single()).isEqualTo(2);
        String before=get(id,token);
        assertThat(revision(before)).isEqualTo(revision(state));
        String preview=post(id,"preview",token,revision(state),200);
        assertThat(JsonPath.<Boolean>read(preview,"$.valid")).isTrue();
        assertThat(JsonPath.<String>read(preview,"$.zone_areas.A")).isEqualTo("A");
        state=patch(id,token,revision(state),metadata("FOOTBALL"),200);
        String complete=post(id,"complete",token,revision(state),200);
        UUID layout=UUID.fromString(JsonPath.read(complete,"$.layout_id"));
        assertThat(jdbc.sql("SELECT count(*) FROM seat_map WHERE id=:id").param("id",layout).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM zone WHERE seat_map_id=:id").param("id",layout).query(Integer.class).single()).isOne();
        assertThat(post(id,"complete",token,revision(state),200)).isEqualTo(complete);
    }
    @Test void ownerIsolationAndRevisionPreventCrossAdminChangesAndLostUpdates() {
        String owner=tokenFor(Role.ADMIN),other=tokenFor(Role.ADMIN);String s=create(client,owner,null);UUID id=id(s);
        client.get().uri(ROOT+"/{id}",id).headers(h->h.setBearerAuth(other)).exchange().expectStatus().isNotFound();
        client.patch().uri(ROOT+"/{id}",id).headers(h->h.setBearerAuth(other)).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("revision",0,"step","LAYOUT")).exchange().expectStatus().isNotFound();
        long rev=revision(s);
        patch(id,owner,rev,Map.of("layout_name","A"),200);
        patch(id,owner,rev,Map.of("layout_name","B"),409);
        assertThat(JsonPath.<String>read(get(id,owner),"$.draft_data.layout_name")).isEqualTo("A");
    }
    @ParameterizedTest @ValueSource(strings={"<script/>","<rect onload='x()'/>","<use href='https://example.com/x'/>"})
    void unsafeFileIsRejectedAtUploadAndNotStored(String content) {
        String token=tokenFor(Role.ADMIN);String state=create(client,token,null);UUID id=id(state);
        uploadResponse(client,token,id,revision(state),bytes(svg(content)),"image/svg+xml")
                .expectStatus().isEqualTo(422).expectBody().jsonPath("$.code").isEqualTo("INVALID_SEAT_LAYOUT");
        assertThat(jdbc.sql("SELECT count(*) FROM venue_import_file WHERE session_id=:id").param("id",id).query(Integer.class).single()).isZero();
    }
    @Test void deduplicatesFilesAndCanRemoveAnInvalidFile() {
        String token=tokenFor(Role.ADMIN);String init=create(client,token,null);UUID id=id(init);
        String s=upload(client,token,id,revision(init),bytes(BASE),"image/svg+xml");long rev=revision(s);
        s=upload(client,token,id,rev,bytes(BASE),"image/svg+xml");assertThat(revision(s)).isEqualTo(rev);
        uploadResponse(client,token,id,rev,bytes("<svg>"),"image/svg+xml")
                .expectStatus().isEqualTo(422).expectBody().jsonPath("$.code").isEqualTo("INVALID_SEAT_LAYOUT");
        String fileId=JsonPath.<List<String>>read(s,"$.files[*].id").getFirst();
        client.delete().uri(ROOT+"/{id}/files/{file}?revision={rev}",id,fileId,rev).headers(h->h.setBearerAuth(token))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.files.length()").isEqualTo(0);
    }
    @Test void rejectsWrongMediaTypeAndOversizedRequest() {
        String token=tokenFor(Role.ADMIN);String init=create(client,token,null);UUID id=id(init);long rev=revision(init);
        uploadResponse(client,token,id,rev,bytes(BASE),"text/plain").expectStatus().isEqualTo(415);
        var upload=new vingo.catalog.VenueImportService.Upload("large.svg",new byte[10*1024*1024+1]);
        var service=applicationImports;
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test").header("alg","HS256")
                    .subject(jdbc.sql("SELECT owner_id FROM venue_import_session WHERE id=:id").param("id",id).query(UUID.class).single().toString())
                    .claim("role","ADMIN").build(),List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        try { assertThatThrownBy(()->service.upload(id,rev,List.of(upload))).isInstanceOf(vingo.shared.error.DomainException.class)
                .extracting(ex->((vingo.shared.error.DomainException)ex).code()).isEqualTo(vingo.shared.error.ErrorCode.PAYLOAD_TOO_LARGE); }
        finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
    @Test void cancelledAndExpiredDraftsCannotBeMutatedAndCleanupIsIdempotent() {
        String token=tokenFor(Role.ADMIN);String init=create(client,token,null);UUID id=id(init);
        String s=upload(client,token,id,revision(init),bytes(BASE),"image/svg+xml");post(id,"cancel",token,revision(s),200);
        assertThat(JsonPath.<String>read(post(id,"cancel",token,revision(s),200),"$.status")).isEqualTo("CANCELLED");
        patch(id,token,revision(s),Map.of("layout_name","X"),409);
        UUID expiring=id(create(client,token,null));clock.advance(Duration.ofDays(31));
        client.get().uri(ROOT+"/{id}",expiring).headers(h->h.setBearerAuth(token)).exchange().expectStatus().isNotFound();
        int n=cleanup.cleanupBatch();assertThat(n).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM venue_import_file WHERE session_id=:id").param("id",id).query(Integer.class).single()).isZero();
        assertThat(cleanup.cleanupBatch()).isZero();
    }
    @Test void failureDuringCompletionRollsBackVenueAndGeometryAndCanBeRetried() {
        String token=tokenFor(Role.ADMIN);String init=create(client,token,null);UUID id=id(init);
        String s=upload(client,token,id,revision(init),bytes(BASE),"image/svg+xml");s=upload(client,token,id,revision(s),bytes(DETAIL),"image/svg+xml");
        s=patch(id,token,revision(s),metadata("CONCERT"),200);
        // Vi phạm metadata được chặn trước khi ghi, cùng phiên vẫn sửa và retry được.
        s=patch(id,token,revision(s),Map.of("layout_name","X","layout_type","CONCERT","venue",Map.of("name","X","address","A","city","HN","lat",100)),200);
        long count=jdbc.sql("SELECT count(*) FROM venue").query(Long.class).single();post(id,"complete",token,revision(s),400);
        assertThat(jdbc.sql("SELECT count(*) FROM venue").query(Long.class).single()).isEqualTo(count);
        assertThat(JsonPath.<String>read(get(id,token),"$.id")).isEqualTo(id.toString());
        s=patch(id,token,revision(s),metadata("CONCERT"),200);post(id,"complete",token,revision(s),200);
    }
    @Test void databaseFailureAfterVenueInsertLeavesDraftAndNoPartialVenue() {
        String token=tokenFor(Role.ADMIN);String init=create(client,token,null);UUID id=id(init);
        String s=upload(client,token,id,revision(init),bytes(BASE),"image/svg+xml");s=upload(client,token,id,revision(s),bytes(DETAIL),"image/svg+xml");
        var metadata=new java.util.HashMap<String,Object>(metadata("CONCERT"));
        String marker="FAIL_"+UUID.randomUUID();metadata.put("layout_name",marker);s=patch(id,token,revision(s),metadata,200);
        String function="test_fail_overview_"+UUID.randomUUID().toString().replace("-","");
        jdbc.sql("CREATE FUNCTION "+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.name='"+marker+"' THEN RAISE EXCEPTION 'injected test failure'; END IF; RETURN NEW; END $$").update();
        jdbc.sql("CREATE TRIGGER "+function+" BEFORE INSERT ON seat_map FOR EACH ROW EXECUTE FUNCTION "+function+"()").update();
        long venues=jdbc.sql("SELECT count(*) FROM venue").query(Long.class).single();
        try {
            post(id,"complete",token,revision(s),500);
            assertThat(jdbc.sql("SELECT count(*) FROM venue").query(Long.class).single()).isEqualTo(venues);
            assertThat(JsonPath.<String>read(get(id,token),"$.id")).isEqualTo(id.toString());
        } finally {
            jdbc.sql("DROP TRIGGER "+function+" ON seat_map").update();jdbc.sql("DROP FUNCTION "+function+"()").update();
        }
        post(id,"complete",token,revision(s),200);
    }
    @Test void concurrentCompletionReturnsOnePersistedResult() throws Exception {
        String token=tokenFor(Role.ADMIN);String init=create(client,token,null);UUID id=id(init);
        String s=upload(client,token,id,revision(init),bytes(BASE),"image/svg+xml");s=upload(client,token,id,revision(s),bytes(DETAIL),"image/svg+xml");
        s=patch(id,token,revision(s),metadata("FOOTBALL"),200);long rev=revision(s);
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(()->{start.await();return client.post().uri(ROOT+"/{id}/complete",id).headers(h->h.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON).body(Map.of("revision",rev)).exchange().returnResult(String.class).getStatus().value();});
            var b=executor.submit(()->{start.await();return client.post().uri(ROOT+"/{id}/complete",id).headers(h->h.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON).body(Map.of("revision",rev)).exchange().returnResult(String.class).getStatus().value();});
            start.countDown();
            assertThat(List.of(a.get(30,java.util.concurrent.TimeUnit.SECONDS),b.get(30,java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactly(200, 200);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM venue_import_session WHERE id=:id AND status='COMPLETED'").param("id",id).query(Integer.class).single()).isOne();
    }
    @Test void enforcesFileCountQuotaAndKeepsManifestUnchangedOnFailure() {
        String token=tokenFor(Role.ADMIN);String init=create(client,token,null);UUID id=id(init);long rev=revision(init);
        for(int i=0;i<32;i++) {
            String detail=DETAIL.replace("ZONE__A__","ZONE__A"+i+"__");
            rev=revision(upload(client,token,id,rev,bytes(detail),"image/svg+xml"));
        }
        uploadResponse(client,token,id,rev,bytes(BASE),"image/svg+xml").expectStatus().isEqualTo(413).expectBody().jsonPath("$.code").isEqualTo("PAYLOAD_TOO_LARGE");
        String after=get(id,token);assertThat(revision(after)).isEqualTo(rev);assertThat(JsonPath.<List<?>>read(after,"$.files")).hasSize(32);
    }
    @Test void listsOnlyOwnersDraftsWithCursorAndWithoutSvgPayload() {
        String token=tokenFor(Role.ADMIN);UUID first=id(create(client,token,null));String s2=create(client,token,null);UUID second=id(s2);create(client,tokenFor(Role.ADMIN),null);
        upload(client,token,second,revision(s2),bytes(BASE),"image/svg+xml");
        var page=client.get().uri(ROOT+"?limit=1").headers(h->h.setBearerAuth(token)).exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(page).contains(second.toString()).doesNotContain("sanitized_svg");
        client.get().uri(ROOT+"?limit=1&cursor="+second).headers(h->h.setBearerAuth(token)).exchange().expectStatus().isOk().expectBody().jsonPath("$.data[0].id").isEqualTo(first.toString());
        client.get().uri(ROOT+"?cursor=invalid").headers(h->h.setBearerAuth(token)).exchange().expectStatus().isBadRequest();
    }
    @Test void rejectsMoreThan32PartsEvenWhenTheirChecksumsAreEqual() {
        String token=tokenFor(Role.ADMIN);String init=create(client,token,null);UUID id=id(init);
        var parts=new org.springframework.http.client.MultipartBodyBuilder();parts.part("revision",Long.toString(revision(init)));
        for(int i=0;i<33;i++) parts.part("files",new org.springframework.core.io.ByteArrayResource(bytes(BASE)) {
            @Override public String getFilename(){return "same.svg";}
        }).contentType(MediaType.parseMediaType("image/svg+xml"));
        client.post().uri(ROOT+"/{id}/files",id).headers(h->h.setBearerAuth(token)).contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts.build()).exchange().expectStatus().isEqualTo(413).expectBody().jsonPath("$.code").isEqualTo("PAYLOAD_TOO_LARGE");
    }
    @Test void addsAnotherLayoutUsingPublishedOverviewAndRejectsDifferentVenue() {
        String token=tokenFor(Role.ADMIN);var event=catalog.event();
        UUID first=VenueImportHttpFixture.importLayout(client,token,event.venueId(),"Football",DETAIL);
        UUID base=jdbc.sql("SELECT venue_map_id FROM seat_map WHERE id=:id").param("id",first).query(UUID.class).single();
        var s=client.post().uri(ROOT).headers(h->h.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("target_venue_id",event.venueId(),"overview_id",base)).exchange().expectStatus().isCreated().expectBody(String.class).returnResult().getResponseBody();
        UUID id=id(s);s=upload(client,token,id,revision(s),bytes(DETAIL),"image/svg+xml");
        s=patch(id,token,revision(s),Map.of("layout_name","Concert","layout_type","CONCERT"),200);
        String result=post(id,"complete",token,revision(s),200);assertThat(JsonPath.<String>read(result,"$.overview_id")).isEqualTo(base.toString());
        client.post().uri(ROOT).headers(h->h.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("target_venue_id",catalog.event().venueId(),"overview_id",base)).exchange().expectStatus().isBadRequest();
    }
    @ParameterizedTest @ValueSource(strings={"","/files","/preview","/complete","/cancel"})
    void adminOperationsRequireAuthenticationAndRejectBuyers(String suffix) {
        String url=ROOT+"/"+UUID.randomUUID()+suffix;
        client.post().uri(url).contentType(MediaType.APPLICATION_JSON).body(Map.of("revision",0)).exchange().expectStatus().isUnauthorized();
        client.post().uri(url).headers(h->h.setBearerAuth(tokenFor(Role.BUYER))).contentType(MediaType.APPLICATION_JSON).body(Map.of("revision",0)).exchange().expectStatus().isForbidden();
    }
    private String get(UUID id,String token){return client.get().uri(ROOT+"/{id}",id).headers(h->h.setBearerAuth(token)).exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();}
    private String post(UUID id,String operation,String token,long revision,int status){return client.post().uri(ROOT+"/{id}/{op}",id,operation).headers(h->h.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON).body(Map.of("revision",revision)).exchange().expectStatus().isEqualTo(status).expectBody(String.class).returnResult().getResponseBody();}
    private String patch(UUID id,String token,long revision,Map<String,?> data,int status){return client.patch().uri(ROOT+"/{id}",id).headers(h->h.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON).body(Map.of("revision",revision,"draft_data",data)).exchange().expectStatus().isEqualTo(status).expectBody(String.class).returnResult().getResponseBody();}
    private static Map<String,?> metadata(String type){return Map.of("layout_name","Main","layout_type",type,"venue",Map.of("name","Stadium","address","Address","city","HN"));}
    private static String svg(String content){return "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'>"+content+"</svg>";}
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
}
