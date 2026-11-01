package com.docmind.service;

import com.docmind.config.AppProperties;
import com.docmind.entity.DocumentMetadata;
import com.docmind.entity.DocumentStatus;
import com.docmind.entity.User;
import com.docmind.exception.DocumentProcessingException;
import com.docmind.repository.DocumentMetaDataRepo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentMetaDataService.class);

    private final VectorStore vectorStore;
    private final DocumentMetaDataRepo documentMetaDataRepo;
    private final AppProperties appProperties;

    public int ingest(DocumentMetadata metadata, List<Document> parseDocs) {
        log.info("Parsing document: {}, size :{} bytes, contentType:{}",metadata.getFilename(), metadata.getFileSize(), metadata.getContentType());
        try {
            metadata.setStatus(DocumentStatus.PROCESSING);
            metadata.setTotalPages(parseDocs.size());

            //1. Text chunking using TokenTextSplitter

            TokenTextSplitter tokenTextSplitter = TokenTextSplitter
                    .builder()
                    .withChunkSize(appProperties.getRag().getChunkSize())
                    .withMinChunkSizeChars(appProperties.getRag().getMinChunkSizeChars())
                    .withMinChunkLengthToEmbed(appProperties.getRag().getMinChunkLengthToEmbed())
                    .withMaxNumChunks(appProperties.getRag().getMaxNumChunk())
                    .build();

            List<Document> chunks = tokenTextSplitter.apply(parseDocs);

            if(chunks.isEmpty()){
                metadata.setStatus(DocumentStatus.FAILED);
                metadata.setErrorMessage("Document appears to be empty or unscannable");
                documentMetaDataRepo.save(metadata);
                return 0;
            }

            // 2. Meta data enrichment on each chunks
            List<Document> enrichedChunks = new ArrayList<>();

            for(int i=0; i< chunks.size();i++){
                Document chunk = chunks.get(i);
                Map<String, Object> enrichedMetaData = new HashMap<>(chunk.getMetadata());
                enrichedMetaData.put("documentId", metadata.getId().toString());
                enrichedMetaData.put("fileName", metadata.getFilename());
                enrichedMetaData.put("contentType", metadata.getContentType());
                enrichedMetaData.put("chunkIndex",i);
                enrichedMetaData.put("user_id", metadata.getUser().getId().toString());

                // Preserve or calculate page no if avl
                Object pageNumber = chunk.getMetadata().get("page_number");
                if(pageNumber == null)
                    pageNumber = chunk.getMetadata().get("pageNumber");

                if(pageNumber != null){
                    enrichedMetaData.put("pageNumber", pageNumber);
                }

                Document enrichedDoc = new Document(chunk.getText(), enrichedMetaData);
                enrichedChunks.add(enrichedDoc);
            }

            // 3. Write chunks and embeddings to PGVector
            log.info(
                    "Writing {} vector chunks to PgVectorStore for document: {}",
                    enrichedChunks.size(),
                    metadata.getFilename()
            );

            try {
                vectorStore.add(enrichedChunks);

                log.info(
                        "Successfully stored {} vector chunks for document: {}",
                        enrichedChunks.size(),
                        metadata.getFilename()
                );

            } catch (Exception e) {
                log.error(
                        "PGVector storage failed. Document={}, chunks={}, error={}",
                        metadata.getFilename(),
                        enrichedChunks.size(),
                        e.getMessage(),
                        e
                );

                throw e;
            }
//            4. update document status to index

            metadata.setStatus(DocumentStatus.INDEXED);
            metadata.setTotalChunks(enrichedChunks.size());
            metadata.setErrorMessage(null);
            documentMetaDataRepo.save(metadata);

            log.info("Successfully indexed document [id={}, name={}, chunks={}]",metadata.getId(), metadata.getFilename(), metadata.getTotalChunks());

            return enrichedChunks.size();
        } catch (Exception e){
            log.error("Failed to ingest document into vector store:{}", metadata.getFilename(), e);
            metadata.setStatus(DocumentStatus.FAILED);
            metadata.setErrorMessage(e.getMessage());
            documentMetaDataRepo.save(metadata);
            throw new DocumentProcessingException("Failed to index document: " + e.getMessage(),e);

        }
    }
}
