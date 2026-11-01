package com.docmind.controller;

import com.docmind.dto.*;
import com.docmind.entity.User;
import com.docmind.service.RagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;

@RestController
@RequestMapping("api/v1/chat")
@Tag(
        name = "Chats Management",
        description = "Endpoints for chats and listing as well as managing documents and their vectors."
)
@RequiredArgsConstructor
public class ChatController {

    private final RagService ragService;

    @PostMapping("/query")
    @Operation(
            summary = "Ask question against all documents or a specific document with citations"
    )
    public ResponseEntity<ApiResponse<ChatResponseDto>> askQuestions(
            @Valid
            @RequestBody ChatRequestDto requestDto,
            Authentication authentication
            ){
        User user = (User) authentication.getPrincipal();
       ChatResponseDto responseDto =  ragService.askQuestion(requestDto, user);
       return ResponseEntity.ok(
               ApiResponse.<ChatResponseDto>
               builder()
                       .success(true)
                       .message(null)
                       .data(responseDto)
                       .timestamp(LocalDateTime.now())
                       .build()
       );
    }

    @PostMapping("/stream")
    @Operation(
            summary = "Stream real-time Q&A answer tokens via Server-sent Events (SSE)"
    )
    public Flux<String> streamQuestions(
            @Valid
            @RequestBody ChatRequestDto requestDto,
            Authentication authentication

    ){
        User user = (User) authentication.getPrincipal();
        return ragService.streamQuestionAnswer(requestDto, user);
    }

    @PostMapping("/search/similarity")
    @Operation(
            summary = "Perform semantic similarity search on stored document vectors"
    )
    public ResponseEntity<ApiResponse<SearchResultDto>> searchSimilar(
            @Valid
            @RequestBody SearchRequestDto requestDto,
            Authentication authentication
    ){
        User user = (User) authentication.getPrincipal();
        SearchResultDto resultDto =  ragService.searchSimilarChunks(requestDto, user);
        return ResponseEntity.ok(
                ApiResponse
                        .<SearchResultDto>
                        builder()
                .success(true)
                .message(null)
                .data(resultDto)
                .timestamp(LocalDateTime.now())
                .build());
    }



}
