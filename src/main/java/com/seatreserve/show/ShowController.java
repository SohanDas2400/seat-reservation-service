package com.seatreserve.show;

import com.seatreserve.config.AppProperties;
import com.seatreserve.web.UnauthorizedException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ShowController {

    private final ShowService showService;
    private final AppProperties props;

    public ShowController(ShowService showService, AppProperties props) {
        this.showService = showService;
        this.props = props;
    }

    /** Admin only: create a show with all its seats available. Guarded by the X-Admin-Token header. */
    @PostMapping("/shows")
    public ResponseEntity<ShowResponse> create(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken,
            @Valid @RequestBody CreateShowRequest request) {
        requireAdmin(adminToken);
        return ResponseEntity.status(HttpStatus.CREATED).body(showService.create(request));
    }

    @GetMapping("/shows/{id}")
    public ShowResponse get(@PathVariable String id) {
        return showService.get(id);
    }

    private void requireAdmin(String adminToken) {
        if (adminToken == null || !adminToken.equals(props.getAdminToken())) {
            throw new UnauthorizedException("valid X-Admin-Token required to create a show");
        }
    }
}
