package kr.hvy.blog.modules.file.adapter.out;

import java.util.List;
import kr.hvy.blog.modules.file.application.port.out.FileManagementPort;
import kr.hvy.blog.modules.file.domain.File;
import kr.hvy.blog.modules.file.domain.FileMapper;
import kr.hvy.blog.modules.file.adapter.out.entity.FileEntity;
import kr.hvy.blog.modules.file.adapter.out.persistence.JpaFileRepository;
import kr.hvy.blog.modules.post.domain.PostMapper;
import kr.hvy.blog.modules.post.adapter.out.entity.PostEntity;
import kr.hvy.common.layer.OutputAdapter;
import lombok.RequiredArgsConstructor;

@OutputAdapter
@RequiredArgsConstructor
public class FileManagementAdapter implements FileManagementPort {

  private final FileMapper fileMapper;
  private final PostMapper postMapper;
  private final JpaFileRepository jpaFileRepository;

  @Override
  public File save(File file) {
    FileEntity fileEntity = fileMapper.toEntity(file);
    PostEntity post = postMapper.toEntity(file.getPost());
    fileEntity.setPost(post);

    FileEntity saved = jpaFileRepository.save(fileEntity);
    return fileMapper.toDomain(saved);
  }

  @Override
  public File findById(Long id) {
    return jpaFileRepository.findById(id)
        .map(fileMapper::toDomain)
        .orElseThrow(() -> new RuntimeException("File not found"));
  }

  @Override
  public void deleteById(Long id) {
    jpaFileRepository.deleteById(id);
  }

  @Override
  public List<File> findByPostId(Long postId) {
    return jpaFileRepository.findByPostIdOrderByOriginNameAsc(postId).stream()
        .map(fileMapper::toDomain)
        .toList();
  }
}
