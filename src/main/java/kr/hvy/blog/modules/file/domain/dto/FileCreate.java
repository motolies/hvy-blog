package kr.hvy.blog.modules.file.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
public class FileCreate {
    @NotNull(message = "게시글 아이디는 필수 입니다. ")
    private Long postId;
    @NotNull(message = "파일은 필수 입니다. ")
    private MultipartFile file;
}
