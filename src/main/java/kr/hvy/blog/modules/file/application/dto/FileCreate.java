package kr.hvy.blog.modules.file.application.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;
import org.springframework.web.multipart.MultipartFile;

@Value
@Builder
@Jacksonized
public class FileCreate {

  @NotNull(message = "게시글 아이디는 필수 입니다. ")
  Long postId;

  @NotNull(message = "파일은 필수 입니다. ")
  MultipartFile file;
}
