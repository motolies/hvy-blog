package kr.hvy.blog.modules.file.domain;

import java.io.IOException;
import kr.hvy.blog.modules.file.domain.dto.FileCreate;
import kr.hvy.blog.modules.post.domain.Post;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
public class FileService {


  public File create(Post post, FileCreate fileCreate, Resource resource, String relativePath) throws IOException {
    return File.builder()
        .post(post)
        .originName(fileCreate.getFile().getOriginalFilename())
        .fileSize(resource.contentLength())
        .type(fileCreate.getFile().getContentType())
        .path(relativePath)
        .build();
  }

}
