package com.tvpirate.backend.favourite;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.tvpirate.backend.favourite.dto.AddFavouriteRequest;
import com.tvpirate.backend.favourite.dto.FavouriteRowDto;
import com.tvpirate.backend.security.AuthedUser;

/** The favourites API behind the heart buttons. GET seeds every page with
 * one shared list; PUT/DELETE are idempotent for optimistic retries.
 * vault:favourites-deep-dive#schema */
@RestController
@RequestMapping("/api/favourites")
public class FavouriteController {

    private final FavouriteService favouriteService;

    public FavouriteController(FavouriteService favouriteService) {
        this.favouriteService = favouriteService;
    }

    @GetMapping
    public List<FavouriteRowDto> list(Authentication authentication) {
        AuthedUser principal = (AuthedUser) authentication.getPrincipal();
        return favouriteService.list(principal.id());
    }

    @PutMapping
    public ResponseEntity<Void> add(@RequestBody AddFavouriteRequest request,
                                    Authentication authentication) {
        if (!request.mediaType().equals("movie") && !request.mediaType().equals("tv")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mediaType must be movie or tv");
        }
        AuthedUser principal = (AuthedUser) authentication.getPrincipal();
        favouriteService.add(principal.id(), request.tmdbId(), request.mediaType());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{type}/{tmdbId}")
    public ResponseEntity<Void> remove(@PathVariable String type,
                                       @PathVariable long tmdbId,
                                       Authentication authentication) {
        if (!type.equals("movie") && !type.equals("tv")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type must be movie or tv");
        }
        AuthedUser principal = (AuthedUser) authentication.getPrincipal();
        favouriteService.remove(principal.id(), tmdbId, type);
        return ResponseEntity.noContent().build();
    }
}
