package com.docmind.controller;

import com.docmind.dto.ApiResponse;
import com.docmind.dto.DocumentMetadataDto;
import com.docmind.dto.DocumentResponseDto;
import com.docmind.entity.User;
import com.docmind.service.DocumentMetaDataService;
import com.docmind.service.DocumentParserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@AllArgsConstructor
@RequestMapping("/api/v1/documents")
@Tag(
        name = "Document Management",
        description = "Endpoints for uploading and listing as well as managing documents and their vectors."
)
public class DocumentController {
    private final static Logger log = LoggerFactory.getLogger(DocumentParserService.class);

    private final DocumentMetaDataService documentService;
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Upload and index a document(PDF, DOCX, TEXT, MD, CSV)",
            description = "This api is used to upload and index documents files."
    )
    public ResponseEntity<ApiResponse<DocumentResponseDto>> uploadDocument(
            @RequestParam("file")MultipartFile file,
            Authentication authentication
    ){
        // process the file
        log.info("checking authentication");
        User user = (User) authentication.getPrincipal();

        DocumentResponseDto documentResponseDto = this.documentService.uploadAndProcess(file,user);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                ApiResponse.<DocumentResponseDto>builder()
                        .success(true)
                        .data(documentResponseDto)
                        .timestamp(LocalDateTime.now())
                        .message("Document uploaded and indexed successfully")
                        .build()
        );
    }

//    api to upload multiple document
    @PostMapping(value = "/upload-multiple", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Upload and index multiple documents simultaneously",
            description = ""
    )
    public ResponseEntity<ApiResponse<List<DocumentResponseDto>>> uploadMultiple(
            @RequestParam("files") List<MultipartFile> files,
            Authentication authentication
    ){
        User user = (User) authentication.getPrincipal();

        List<DocumentResponseDto> response= documentService.uploadMultipleDocuments(files,user);
       return ResponseEntity.status(HttpStatus.CREATED)
               .body(
                       ApiResponse.<List<DocumentResponseDto>>
               builder()
                               .message("Document uploaded successfully")
                               .success(true)
                               .timestamp(LocalDateTime.now())
                               .data(response)
                               .build()
               );
    }
//    List of all uploaded documents
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    @Operation(
            summary = "List of all uploaded documents and their indexing status for admin"
    )
    public ResponseEntity<ApiResponse<List<DocumentMetadataDto>>> getAllDocuments(){
        List<DocumentMetadataDto> lists = documentService.getAllDocuments();
        return ResponseEntity.ok(
                ApiResponse.<List<DocumentMetadataDto>>
                        builder()
                        .message("All Documents is here")
                        .success(true)
                        .timestamp(LocalDateTime.now())
                        .data(lists)
                        .build()
        );
    }

    @GetMapping("/user")
    @Operation(
            summary = "List of all uploaded documents and their indexing status with their login user"
    )
    public ResponseEntity<ApiResponse<List<DocumentMetadataDto>>> getAllDocumentsOfLoggedInUser(
            Authentication authentication
    ){
        User user = (User) authentication.getPrincipal();
        List<DocumentMetadataDto> lists = documentService.getAllDocumentByUser(user);
        return ResponseEntity.ok(
                ApiResponse.<List<DocumentMetadataDto>>
                                builder()
                        .message("All Documents is here")
                        .success(true)
                        .timestamp(LocalDateTime.now())
                        .data(lists)
                        .build()
        );
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Get documents and their indexing status"
    )
    public ResponseEntity<ApiResponse<DocumentMetadataDto>> getDocumentById(@PathVariable UUID id){
        DocumentMetadataDto document = documentService.getDocumentById(id);
        return ResponseEntity.ok(
                ApiResponse.<DocumentMetadataDto>
                                builder()
                        .message("Single Document by id: "  + id + "here")
                        .success(true)
                        .timestamp(LocalDateTime.now())
                        .data(document)
                        .build()
        );
    }

    @DeleteMapping("/{id}")
    @Operation(
            summary = "List of all uploaded documents and their indexing status"
    )
    public ResponseEntity<ApiResponse<Void>> deleteDocumentById(@PathVariable UUID id){
        documentService.deleteDocumentById(id);
        return ResponseEntity.ok(
                ApiResponse.<Void>
                                builder()
                        .message("Delete Document by id: "  + id +" successfully!")
                        .success(true)
                        .timestamp(LocalDateTime.now())
                        .data(null)
                        .build()
        );
    }
}























