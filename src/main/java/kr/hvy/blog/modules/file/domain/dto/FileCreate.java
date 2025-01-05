package kr.hvy.blog.modules.file.domain.dto;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
public class FileCreate {
    private Long postId;
    private MultipartFile file;
}
