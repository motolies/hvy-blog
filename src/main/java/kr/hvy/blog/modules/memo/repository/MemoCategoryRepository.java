package kr.hvy.blog.modules.memo.repository;

import java.util.List;
import kr.hvy.blog.modules.memo.domain.entity.MemoCategory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoCategoryRepository extends JpaRepository<MemoCategory, Long> {

  List<MemoCategory> findAllByOrderBySeqAscNameAsc();

  boolean existsByName(String name);
}
