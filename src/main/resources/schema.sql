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
    status       CHAR(3) not null,
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

    constraint uk_post_id_status        unique (id, status),
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

