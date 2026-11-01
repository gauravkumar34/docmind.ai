package com.docmind.repository;

import com.docmind.dto.DocumentResponseDto;
import com.docmind.entity.DocumentMetadata;
import com.docmind.entity.DocumentStatus;
import com.docmind.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentMetaDataRepo extends JpaRepository<DocumentMetadata, UUID> {
    List<DocumentMetadata> findAllByOrderByCreatedAtDesc();
    List<DocumentMetadata> findByStatus(DocumentStatus status);

    List<DocumentMetadata> findByUserOrderByCreatedAtDesc(User user);

    Optional<DocumentMetadata> findByIdAndUser(UUID id, User user);
}
