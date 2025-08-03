package kr.hvy.blog.modules.post.repository;

import java.util.List;
import kr.hvy.blog.modules.post.domain.entity.PostSearchEngineEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SearchEngineRepository extends JpaRepository<PostSearchEngineEntity, Long> {

  List<PostSearchEngineEntity> findAll();
}
