create or replace table tb_authority
(
    id   bigint auto_increment
        primary key,
    name varchar(50) null,
    constraint uk_authority_name
        unique (name)
);

create or replace table tb_category
(
    id       varchar(32)  not null        primary key,
    name     varchar(64)  not null,
    seq      int          not null,
    fullName varchar(512) not null,
    fullPath varchar(512) not null,
    parentId varchar(32)  null,
    constraint fk_parent_id
        foreign key (parentId) references tb_category (id)
);

create or replace table tb_post
(
    id           bigint auto_increment        primary key,
    subject      varchar(512)             not null,
    body         longtext                 not null,
    normalBody         longtext                 not null,
    publicAccess bit                      not null,
    mainPage     bit                      not null,
    viewCount    int                      not null,
    categoryId   varchar(32)              not null,
    createdAt    datetime(6)              not null,
    createdBy    varchar(255)             null,
    updatedAt    datetime(6)              not null,
    updatedBy    varchar(255)             null,

    constraint fk_post_category_id        foreign key (categoryId) references tb_category (id)
);

create or replace table tb_file
(
    id         bigint       not null        primary key,
    postId     bigint       not null,
    originName varchar(256) not null,
    type       varchar(512) not null,
    path       varchar(512) not null,
    fileSize   bigint       not null,
    deleted    bit          not null,
    createdAt  datetime(6)  not null,
    createdBy  varchar(255) null,
    constraint fk_file_post
        foreign key (postId) references tb_post (id)
);

create or replace table tb_search_engine
(
    id        bigint auto_increment        primary key,
    name      varchar(32)  not null,
    url       varchar(512) not null,
    seq       int          not null,
    createdAt datetime(6)  not null,
    createdBy varchar(255) null,
    updatedAt datetime(6)  not null,
    updatedBy varchar(255) null

);

create or replace table tb_tag
(
    id        bigint auto_increment        primary key,
    name      varchar(64)  not null,
    createdAt datetime(6)  not null,
    createdBy varchar(255) null,
    constraint uk_tag_name        unique (name)
);

create or replace table tb_post_tag_map
(
    postId bigint not null,
    tagId  bigint not null,
    primary key (postId, tagId),
    constraint fk_post_tag_map_post_id        foreign key (postId) references tb_post (id),
    constraint fk_post_tag_map_tag_id        foreign key (tagId) references tb_tag (id)
);

create or replace table tb_user
(
    id        bigint auto_increment        primary key,
    name      varchar(128) not null,
    loginId   varchar(32)  not null,
    password  varchar(64)  not null,
    isEnabled bit          null,
    constraint uk_user_login_id        unique (loginId)
);

create or replace table tb_user_authority_map
(
    userId      bigint not null,
    authorityId bigint not null,
    primary key (authorityId, userId),
    constraint fk_user_authority_map_authority_id        foreign key (authorityId) references tb_authority (id),
    constraint fk_user_authority_map_user_id        foreign key (userId) references tb_user (id)
);

-- 공통코드 관리 테이블들

create or replace table tb_common_class
(
    name             varchar(64)   not null        primary key,
    displayName      varchar(128)  null,
    description      varchar(512)  null,
    attribute1Name   varchar(64)   null            comment '동적속성1 이름',
    attribute2Name   varchar(64)   null            comment '동적속성2 이름',
    attribute3Name   varchar(64)   null            comment '동적속성3 이름',
    attribute4Name   varchar(64)   null            comment '동적속성4 이름',
    attribute5Name   varchar(64)   null            comment '동적속성5 이름',
    isActive         bit           not null        default 1,
    createdAt        datetime(6)   not null,
    createdBy        varchar(32)   null,
    updatedAt        datetime(6)   null,
    updatedBy        varchar(32)   null
) comment '공통코드 클래스 (코드 그룹 정의)';

create or replace table tb_common_code
(
    className         varchar(64)  not null        comment '클래스명 (복합키1)',
    code              varchar(32)  not null        comment '코드값 (복합키2)',
    name              varchar(64)  not null        comment '코드명',
    description       varchar(512) null           comment '설명',
    attribute1Value   varchar(128) null           comment '동적속성1 값',
    attribute2Value   varchar(128) null           comment '동적속성2 값',
    attribute3Value   varchar(128) null           comment '동적속성3 값',
    attribute4Value   varchar(128) null           comment '동적속성4 값',
    attribute5Value   varchar(128) null           comment '동적속성5 값',
    childClassName    varchar(64)  null           comment '하위클래스명 (NULL이면 leaf 노드)',
    sort              int          not null        default 0           comment '정렬순서',
    isActive          bit          not null        default 1           comment '활성화여부',
    createdAt         datetime(6)  not null,
    createdBy         varchar(32)  null,
    updatedAt         datetime(6)  null,
    updatedBy         varchar(32)  null,

    primary key (className, code),
    constraint fk_common_code_class_name        foreign key (className) references tb_common_class (name)
        on update cascade on delete restrict,
    constraint fk_common_code_child_class_name  foreign key (childClassName) references tb_common_class (name)
        on update cascade on delete set null
) comment '공통코드 (실제 코드값 저장)';

