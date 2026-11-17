package vingo.auth.web;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import vingo.api.AdminAccountsApi;
import vingo.api.model.AdminAccount;
import vingo.api.model.AdminAccountPage;
import vingo.api.model.AdminAccountUpdate;
import vingo.auth.AdminAccountService;

@RestController
public class AdminAccountController implements AdminAccountsApi {
    private final AdminAccountService accounts;
    public AdminAccountController(AdminAccountService accounts) { this.accounts = accounts; }

    @Override
    public ResponseEntity<AdminAccountPage> adminListUsers(String q, String role, String status, String cursor, Integer limit) {
        return ResponseEntity.ok(accounts.list(q, role, status, cursor, limit));
    }

    @Override
    public ResponseEntity<AdminAccount> adminGetUser(UUID userId) { return ResponseEntity.ok(accounts.get(userId)); }

    @Override
    public ResponseEntity<AdminAccount> adminUpdateUser(UUID userId, AdminAccountUpdate body) {
        return ResponseEntity.ok(accounts.update(userId, body));
    }
}
