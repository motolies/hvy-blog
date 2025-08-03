package kr.hvy.blog.modules.file.repository;

import kr.hvy.blog.modules.file.domain.entity.File;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileRepository extends JpaRepository<File, Long> {

}
