package vingo.chat.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import vingo.api.ChatApi;
import vingo.api.model.ChatConfirmRequest;
import vingo.api.model.ChatMessageRequest;
import vingo.api.model.ChatRejectRequest;
import vingo.chat.ChatService;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;
import vingo.shared.security.AgentRequest;
import vingo.shared.security.CurrentUser;

@RestController
@org.springframework.context.annotation.Profile("api")
public class ChatController implements ChatApi {
    private final ChatService service;
    private final CurrentUser currentUser;
    private final HttpServletRequest request;
    public ChatController(ChatService service, CurrentUser currentUser, HttpServletRequest request) {
        this.service = service;
        this.currentUser = currentUser;
        this.request = request;
    }
    @Override
    public ResponseEntity<Map<String, Object>> createChatConversation() {
        return json("POST", "conversations", new byte[0], "");
    }
    @Override
    public ResponseEntity<Map<String, Object>> getChatConversation(UUID id) {
        return json("GET", "conversations/" + id, new byte[0], "");
    }
    @Override
    public ResponseEntity<Map<String, Object>> getChatTurn(UUID turnId) {
        return json("GET", "turns/" + turnId, new byte[0], "");
    }
    @Override
    public ResponseEntity<StreamingResponseBody> sendChatMessage(UUID id, ChatMessageRequest body) {
        return stream("conversations/" + id + "/messages", body);
    }
    @Override
    public ResponseEntity<StreamingResponseBody> confirmChatAction(UUID actionId, ChatConfirmRequest body) {
        return stream("actions/" + actionId + "/confirm", body);
    }
    @Override
    public ResponseEntity<Map<String, Object>> rejectChatAction(UUID actionId, ChatRejectRequest body) {
        return json("POST", "actions/" + actionId + "/reject", service.encode(body), "application/json");
    }
    @Override
    public ResponseEntity<Map<String, Object>> uploadChatFile(MultipartFile file) {
        if (file.getSize() > 10 * 1024 * 1024) throw new DomainException(ErrorCode.PAYLOAD_TOO_LARGE, "Tệp vượt 10 MiB");
        if (file.isEmpty()) throw new DomainException(ErrorCode.VALIDATION_ERROR, "Tệp rỗng");
        String type = file.getContentType();
        if (type == null || !Set.of("image/svg+xml", "image/png", "image/jpeg", "image/webp").contains(type))
            throw new DomainException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Chỉ nhận SVG hoặc ảnh poster");
        String boundary = "vingo-" + UUID.randomUUID();
        // Tên cố định tránh chèn header multipart từ tên file do client cung cấp.
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"upload"
                + (type.equals("image/svg+xml") ? ".svg" : type.equals("image/jpeg") ? ".jpg" : type.equals("image/png") ? ".png" : ".webp")
                + "\"\r\nContent-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            bytes.write(prefix);
            try (var input = file.getInputStream()) { input.transferTo(bytes); }
            bytes.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return json("POST", "uploads", bytes.toByteArray(), "multipart/form-data; boundary=" + boundary);
        } catch (IOException ex) { throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "Không đọc được tệp"); }
    }
    private String token() {
        Object token = request.getAttribute(AgentRequest.SESSION_TOKEN);
        if (!(token instanceof String value)) throw new DomainException(ErrorCode.UNAUTHORIZED, "Cần phiên hợp lệ");
        return value;
    }
    private ResponseEntity<Map<String, Object>> json(String method, String path, byte[] body, String type) {
        var response = service.request(method, path, body, type, false, currentUser.require(), token());
        return ResponseEntity.status(response.statusCode()).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(service.read(response));
    }
    private ResponseEntity<StreamingResponseBody> stream(String path, Object body) {
        var response = service.request("POST", path, service.encode(body), "application/json", true, currentUser.require(), token());
        // Timeout riêng cho chat: một turn có thể kéo dài 120 giây.
        WebAsyncUtils.getAsyncManager(request)
                .registerCallableInterceptor(ChatController.class, new CallableProcessingInterceptor() {
                    @Override
                    public <T> void preProcess(NativeWebRequest web,
                                               Callable<T> task) {
                        web.getNativeRequest(HttpServletRequest.class).getAsyncContext().setTimeout(130_000L);
                    }
                    @Override
                    public <T> Object handleTimeout(NativeWebRequest web,
                                                     Callable<T> task) throws IOException {
                        response.body().close();
                        return RESULT_NONE;
                    }
                    @Override
                    public <T> void afterCompletion(NativeWebRequest web,
                                                    Callable<T> task) throws IOException {
                        response.body().close();
                    }
                });
        StreamingResponseBody stream = output -> {
            try (var input = response.body()) {
                byte[] buffer = new byte[8192];
                int size;
                while ((size = input.read(buffer)) != -1) {
                    output.write(buffer, 0, size);
                    output.flush();
                }
            }
        };
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(org.springframework.http.CacheControl.noStore()).header("X-Accel-Buffering", "no").body(stream);
    }
}
