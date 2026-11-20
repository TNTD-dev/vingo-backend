package vingo.catalog.web;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import vingo.api.AdminWorkspaceApi;
import vingo.api.model.AdminLayoutTree;
import vingo.api.model.LayoutType;
import vingo.catalog.AdminLayoutTreeService;

@RestController
public class AdminWorkspaceController implements AdminWorkspaceApi {
    private final AdminLayoutTreeService trees;

    public AdminWorkspaceController(AdminLayoutTreeService trees) {
        this.trees = trees;
    }

    @Override
    public ResponseEntity<AdminLayoutTree> getAdminLayoutTree(UUID venueId, LayoutType layoutType,
            String cursor, Integer limit) {
        return ResponseEntity.ok(trees.read(venueId, layoutType, cursor, limit));
    }
}
