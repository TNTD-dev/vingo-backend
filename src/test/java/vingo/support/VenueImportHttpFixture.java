package vingo.support;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.w3c.dom.Element;
import com.jayway.jsonpath.JsonPath;

/** Driver HTTP cho luồng nháp v2, dùng chung trong các test catalog. */
public final class VenueImportHttpFixture {
    public static final String ROOT="/api/v1/admin/venue-imports";
    public static final String SAMPLE_DETAIL="""
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1600 1200">
          <rect id="ZONE__B3-T1__SEATED" x="500" y="300" width="100" height="100"/>
          <g id="CONTENT__B3-T1">
            <circle id="SEAT__B3-T1__A__1" cx="532.77" cy="349.88" r="1"/>
            <circle id="SEAT__B3-T1__A__2" cx="540" cy="349.88" r="1"/>
            <circle id="SEAT__B3-T1__B__1" cx="532.77" cy="360" r="1"/>
            <circle id="SEAT__B3-T1__B__2" cx="540" cy="360" r="1"/>
            <path id="PITCH_SIDE__B3-T1" d="M500 300H600"/>
          </g>
          <rect id="ZONE__B11-T2__STANDING__1000" x="700" y="700" width="100" height="100"/>
        </svg>
        """;
    private VenueImportHttpFixture() {}
    public static String create(RestTestClient client,String token,UUID venue) {
        return client.post().uri(ROOT).headers(h->h.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON)
                .body(venue==null?Map.of():Map.of("target_venue_id",venue)).exchange().expectStatus().isCreated()
                .expectBody(String.class).returnResult().getResponseBody();
    }
    public static String upload(RestTestClient client,String token,UUID session,long revision,byte[] bytes,String mediaType) {
        return uploadResponse(client,token,session,revision,bytes,mediaType).expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    }
    public static RestTestClient.ResponseSpec uploadResponse(RestTestClient client,String token,UUID session,long revision,byte[] bytes,String mediaType) {
        var parts=new MultipartBodyBuilder();parts.part("revision",Long.toString(revision));
        var resource=new ByteArrayResource(bytes) {@Override public String getFilename(){return "layout.svg";}};
        parts.part("files",resource).contentType(MediaType.parseMediaType(mediaType));
        return client.post().uri(ROOT+"/{id}/files",session).headers(h->h.setBearerAuth(token)).contentType(MediaType.MULTIPART_FORM_DATA).body(parts.build()).exchange();
    }
    public static UUID importLayout(RestTestClient client,String token,UUID venue,String name,String source) {
        String result=importResponse(client,token,venue,name,source).expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        return UUID.fromString(JsonPath.read(result,"$.layout_id"));
    }
    public static RestTestClient.ResponseSpec importResponse(RestTestClient client,String token,UUID venue,String name,String source) {
        String state=create(client,token,venue);UUID session=id(state);String[] layers=split(source);
        state=upload(client,token,session,revision(state),layers[0].getBytes(StandardCharsets.UTF_8),"image/svg+xml");
        state=upload(client,token,session,revision(state),layers[1].getBytes(StandardCharsets.UTF_8),"image/svg+xml");
        var metadata=new java.util.HashMap<String,Object>();metadata.put("layout_type","FOOTBALL");if(name!=null)metadata.put("layout_name",name);
        var patch=client.patch().uri(ROOT+"/{id}",session).headers(h->h.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("revision",revision(state),"draft_data",metadata)).exchange();
        if(name!=null&&name.length()>200) return patch;
        state=patch.expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        return client.post().uri(ROOT+"/{id}/complete",session).headers(h->h.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("revision",revision(state))).exchange();
    }
    public static long revision(String state) {return JsonPath.<Number>read(state,"$.revision").longValue();}
    public static UUID id(String state) {return UUID.fromString(JsonPath.read(state,"$.id"));}
    public static String[] split(String source) {
        try {
            var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            String[] result=new String[2];
            for(int layer=0;layer<2;layer++) {
                var doc=factory.newDocumentBuilder().parse(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
                var nodes=doc.getElementsByTagName("*");List<Element> remove=new java.util.ArrayList<>();
                for(int i=0;i<nodes.getLength();i++) {
                    Element e=(Element)nodes.item(i);String id=e.getAttribute("id");
                    boolean detail=List.of("ZONE__","CONTENT__","PITCH_SIDE__","SEAT__").stream().anyMatch(id::startsWith);
                    boolean base=id.equals("PITCH")||id.equals("CIRCULATION")||List.of("GATE__","GATE_LABEL__","AREA__","WALKWAY__","STAGE__").stream().anyMatch(id::startsWith);
                    if(layer==0?detail:base) remove.add(e);
                }
                for(var e:remove) if(e.getParentNode()!=null)e.getParentNode().removeChild(e);
                if(layer==0 && doc.getElementsByTagName("*").getLength()>0) {
                    boolean hasGate=false;for(int i=0;i<doc.getElementsByTagName("*").getLength();i++)if(((Element)doc.getElementsByTagName("*").item(i)).getAttribute("id").startsWith("GATE__"))hasGate=true;
                    if(!hasGate) {Element gate=doc.createElementNS("http://www.w3.org/2000/svg","rect");gate.setAttribute("id","GATE__G1");gate.setAttribute("x","90");gate.setAttribute("y","90");gate.setAttribute("width","2");gate.setAttribute("height","2");doc.getDocumentElement().appendChild(gate);}
                }
                var out=new StringWriter();TransformerFactory.newInstance().newTransformer().transform(new DOMSource(doc.getDocumentElement()),new StreamResult(out));result[layer]=out.toString();
            }
            return result;
        }catch(Exception ex){throw new IllegalStateException(ex);}
    }
}
