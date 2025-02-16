package kr.hvy.blog.modules.file.adapter.out.persistence;

import java.util.List;
import kr.hvy.blog.modules.file.adapter.out.entity.FileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaFileRepository extends JpaRepository<FileEntity, Long> {

  List<FileEntity> findByPostIdOrderByOriginNameAsc(Long postId);
}
