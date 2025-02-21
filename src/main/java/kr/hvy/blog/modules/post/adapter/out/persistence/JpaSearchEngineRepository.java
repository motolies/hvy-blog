package kr.hvy.blog.modules.post.adapter.out.persistence;

import java.util.List;
import kr.hvy.blog.modules.post.adapter.out.entity.PostSearchEngineEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaSearchEngineRepository extends JpaRepository<PostSearchEngineEntity, Long> {

  List<PostSearchEngineEntity> findAll();
}
