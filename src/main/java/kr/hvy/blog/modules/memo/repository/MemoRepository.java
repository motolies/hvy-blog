package kr.hvy.blog.modules.memo.repository;

import kr.hvy.blog.modules.memo.domain.entity.Memo;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoRepository extends JpaRepository<Memo, Long> {

  boolean existsByCategoryId(Long categoryId);
}
