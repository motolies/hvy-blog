package kr.hvy.blog.modules.post.domain.dto;

import java.io.Serial;
import java.io.Serializable;
import lombok.Data;

@Data
public class PostPrevNextResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = -8943731195456273018L;

    private int prev;
    private int next;

}
