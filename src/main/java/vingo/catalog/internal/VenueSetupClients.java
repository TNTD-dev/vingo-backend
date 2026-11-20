package vingo.catalog.internal;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vingo.api.model.LocationSuggestion;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

/** HTTP ngoài transaction; không đưa credential hoặc body lỗi provider vào log. */
@Component
public class VenueSetupClients {
    private final JsonMapper json;
    private final String geoKey;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public VenueSetupClients(JsonMapper json,
        @Value("${GEOAPIFY_API_KEY:}") String geoKey) {
        this.json = json;
        this.geoKey = geoKey;
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private JsonNode geo(String path){
        if(geoKey.isBlank())throw unavailable();
        return json.readTree(send(HttpRequest.newBuilder(URI.create("https://api.geoapify.com/v1/"+path+"&apiKey="+encode(geoKey))).timeout(Duration.ofSeconds(10)).GET().build()));
    }
    public List<LocationSuggestion> suggest(String query,int limit) {
        if(query==null||query.strip().length()<3||query.length()>200||limit<1||limit>10)throw invalid("Từ khóa từ 3 đến 200 ký tự; tối đa 10 kết quả.");
        List<LocationSuggestion> result=new ArrayList<>();
        for(var item:geo("geocode/autocomplete?text="+encode(query.strip())+"&limit="+limit+"&lang=vi").path("features"))result.add(location(item.path("properties")));
        return result;
    }
    public LocationSuggestion reverse(BigDecimal lat,BigDecimal lng) {
        coordinates(lat,lng);
        var features=geo("geocode/reverse?lat="+lat+"&lon="+lng+"&lang=vi").path("features");
        if(features.isEmpty())throw new DomainException(ErrorCode.NOT_FOUND,"Không tìm thấy địa chỉ của vị trí này.");
        return location(features.get(0).path("properties")).lat(lat).lng(lng);
    }
    private static LocationSuggestion location(JsonNode properties) {
        String address = properties.path("formatted").asText("");
        String city = properties.path("state").asText("");
        if (city.isBlank()) city = properties.path("city").asText(null);
        return new LocationSuggestion().address(address).label(address)
                .lat(properties.path("lat").decimalValue()).lng(properties.path("lon").decimalValue())
                .city(VenueRepository.canonicalCity(city)).district(properties.path("district").asText(null));
    }

    private byte[] send(HttpRequest request) {
        return responseBody(exchange(request));
    }
    private static byte[] responseBody(HttpResponse<byte[]> response) {
        if(response.statusCode()<200||response.statusCode()>=300)throw unavailable();
        return response.body();
    }
    private HttpResponse<byte[]> exchange(HttpRequest request) {
        try {
            return http.send(request,HttpResponse.BodyHandlers.ofByteArray());
        } catch(InterruptedException ex){Thread.currentThread().interrupt();throw unavailable();}
        catch(java.io.IOException|IllegalArgumentException ex){throw unavailable();}
    }
    public static void coordinates(BigDecimal lat,BigDecimal lng){if(lat==null||lng==null||lat.abs().compareTo(BigDecimal.valueOf(90))>0||lng.abs().compareTo(BigDecimal.valueOf(180))>0)throw invalid("Tọa độ không hợp lệ.");}
    private static DomainException invalid(String message){return new DomainException(ErrorCode.VALIDATION_ERROR,message);}
    private static DomainException unavailable(){return new DomainException(ErrorCode.SERVICE_UNAVAILABLE,"Dịch vụ bản đồ tạm thời không khả dụng. Vui lòng thử lại.");}
}
