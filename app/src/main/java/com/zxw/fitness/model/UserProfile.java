package com.zxw.fitness.model;

/**
 * 训练账号。数据按账号隔离，切换账号即可看到各自的训练与历史记录。
 */
public class UserProfile {

    /** 未登录/默认账号，首次启动时自动创建。 */
    public static final String DEFAULT_ID = "default";

    private final String id;
    private String name;
    private final long createdAt;
    private final String avatar; // 一个 emoji，作为头像

    public UserProfile(String id, String name, long createdAt, String avatar) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
        this.avatar = avatar;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public String getAvatar() {
        return avatar;
    }

    /** 是否是可以删除的普通账号（默认账号不可删除）。 */
    public boolean isDeletable() {
        return !DEFAULT_ID.equals(id);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserProfile)) {
            return false;
        }
        return id.equals(((UserProfile) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
