package kr.hvy.blog.modules.post.domain.dto;

import java.io.Serial;
import java.io.Serializable;
import lombok.Data;

@Data
public class PostNoBodyResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = -1192209274850300944L;

    private int id;
    private String subject;
    private String categoryName;
    private int viewCount;
    private java.sql.Timestamp createDate;
    private java.sql.Timestamp updateDate;

}

