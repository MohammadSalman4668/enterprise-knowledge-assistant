package com.salman.ekg.controller;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.salman.ekg.dto.FeedbackDtos.FeedbackRequest;
import com.salman.ekg.dto.FeedbackDtos.FeedbackResponse;
import com.salman.ekg.security.CurrentUser;
import com.salman.ekg.service.FeedbackService;

@RestController
@RequestMapping("/api/knowledge")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @PostMapping("/feedback")
    public ResponseEntity<FeedbackResponse> submit(@Valid @RequestBody FeedbackRequest request, Authentication auth) {
        FeedbackResponse saved = feedbackService.submit(request, CurrentUser.from(auth));
        return ResponseEntity.created(URI.create("/api/knowledge/feedback/" + saved.id())).body(saved);
    }
}
