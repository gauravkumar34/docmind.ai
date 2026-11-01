package com.docmind.service;

import com.docmind.dto.DocumentMetadataDto;
import com.docmind.dto.DocumentResponseDto;
import com.docmind.entity.DocumentMetadata;
import com.docmind.entity.DocumentStatus;
import com.docmind.entity.User;
import com.docmind.exception.DocumentProcessingException;
import com.docmind.exception.ResourceNotFoundException;
import com.docmind.repository.DocumentMetaDataRepo;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentMetaDataService {
    private static final Logger log = LoggerFactory.getLogger(DocumentMetaDataService.class);

    private final DocumentMetaDataRepo documentMetaDataRepo;
    private final JdbcTemplate jdbcTemplate;
    private final DocumentParserService documentParserService;
    private final ModelMapper modelMapper;
    private final DocumentIngestionService documentIngestionService;

    // method to upload and parse document
    @Transactional
    public DocumentResponseDto uploadAndProcess(MultipartFile file, User user) {
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document";
        String contentType = file.getContentType() != null ? file.getContentType() :"application/octat-stream";

        // document meta data create
        // also used details
       DocumentMetadata documentMetadata =  DocumentMetadata.builder()
                .filename(fileName)
                .contentType(contentType)
                .status(DocumentStatus.UPLOADING)
                .fileSize(file.getSize())
                .createdAt(LocalDateTime.now())
                .user(user)
                .build();

        // save the metadata
//        documentMetadata.setTotalChunks(chunksCreated);
        documentMetadata = documentMetaDataRepo.save(documentMetadata);
        List<Document> parseDocs = null;
        int chunksCreated = 0;
        try{
            // parse the file
            parseDocs = documentParserService.parse(file);

            // ingest service
            chunksCreated = documentIngestionService.ingest(documentMetadata,parseDocs);

        } catch (DocumentProcessingException e){
            documentMetaDataRepo.delete(documentMetadata);
            throw e;
        }


        return DocumentResponseDto
                .builder()
                .id(documentMetadata.getId())
                .fileName(documentMetadata.getFilename())
                .fileSize(documentMetadata.getFileSize())
                .status(documentMetadata.getStatus())
                .chunksCreated(chunksCreated)
                .message("Document successfully processed and index")
                .userId(user.getId())
                .build();
    }

    @Transactional
    public List<DocumentResponseDto> uploadMultipleDocuments(List<MultipartFile> files, User user) {

        List<DocumentResponseDto> responseDtos = new ArrayList<>();

        for (MultipartFile file : files){
           DocumentResponseDto responseDto =  uploadAndProcess(file, user);
           responseDtos.add(responseDto);
        }
        return responseDtos;
    }

    public List<DocumentMetadataDto> getAllDocuments() {
        List<DocumentMetadata> documents =  documentMetaDataRepo.findAllByOrderByCreatedAtDesc();
       return documents.stream()
                .map(documentMetaData -> modelMapper.map(
                        documentMetaData, DocumentMetadataDto.class
                ))
                .toList();
    }

    public List<DocumentMetadataDto> getAllDocumentByUser(User user){
        List<DocumentMetadata> documents =  documentMetaDataRepo.findByUserOrderByCreatedAtDesc(user);
        return  documents.stream()
                .map(documentMetaData -> modelMapper.map(
                        documentMetaData, DocumentMetadataDto.class
                ))
                .toList();
    }
    public DocumentMetadataDto getDocumentById(UUID id) {
        DocumentMetadata documentMetadata =  documentMetaDataRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Document with given id is not found"));
        return modelMapper.map(documentMetadata, DocumentMetadataDto.class);
    }
    public void deleteDocumentById(UUID id) {
        DocumentMetadata documentMetadata =  documentMetaDataRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Document with given id is not found"));

        // delete the vector
        try {
            String deleteVectorsSql = "DELETE FROM vector_store WHERE metadata->>'documentId' = ?";
            int deletedCount = jdbcTemplate.update(deleteVectorsSql,id.toString());
            log.info("Deleted {} vectors chunks for document id {}", deletedCount, id);
        } catch (Exception e){
            log.warn("Could not delete from vector tore directly {}", e.getMessage());
        }


    }


}


























