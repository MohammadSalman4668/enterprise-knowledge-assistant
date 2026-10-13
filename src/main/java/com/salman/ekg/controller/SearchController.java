package com.salman.ekg.controller;

import jakarta.validation.Valid;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.salman.ekg.dto.SearchDtos.SearchRequest;
import com.salman.ekg.dto.SearchDtos.SearchResponse;
import com.salman.ekg.security.CurrentUser;
import com.salman.ekg.service.SearchService;

@RestController
@RequestMapping("/api/knowledge")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping("/search")
    public SearchResponse search(@Valid @RequestBody SearchRequest request, Authentication auth) {
        return searchService.search(request, CurrentUser.from(auth));
    }
}
