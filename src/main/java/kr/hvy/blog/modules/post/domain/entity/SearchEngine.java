package kr.hvy.blog.modules.post.domain.entity;

import jakarta.persistence.*;
import kr.hvy.common.application.domain.embeddable.EventLogEntity;
import lombok.*;


@Entity
@Getter
@Setter
@Builder
@With
@AllArgsConstructor
@NoArgsConstructor
public class SearchEngine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String name;

    @Column(nullable = false, length = 512)
    private String url;

    @Column(nullable = false, length = 11)
    private int seq;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "at", column = @Column(name = "createdAt", columnDefinition = "TIMESTAMP(6)", nullable = false)),
            @AttributeOverride(name = "by", column = @Column(name = "createdBy"))
    })
    @Builder.Default
    private EventLogEntity created = EventLogEntity.defaultValues();

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "at", column = @Column(name = "updatedAt", columnDefinition = "TIMESTAMP(6)", nullable = false)),
            @AttributeOverride(name = "by", column = @Column(name = "updatedBy"))
    })
    @Builder.Default
    private EventLogEntity updated = EventLogEntity.defaultValues();

}
